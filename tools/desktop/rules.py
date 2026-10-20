"""Versioned explicit Java Hook rules for M2; no wildcard classes or bulk auto-hook."""
from __future__ import annotations

import json
import re
from pathlib import Path

IDENTIFIER = re.compile(r"^[A-Za-z_$][A-Za-z\d_$]*$")
CLASS_NAME = re.compile(r"^[A-Za-z_$][A-Za-z\d_$]*(\.[A-Za-z_$][A-Za-z\d_$]*)+$")


def validate_rule(obj: dict, package: str) -> dict:
    if not isinstance(obj, dict):
        raise ValueError("Rule must be an object")
    class_name = obj.get("class_name")
    method_name = obj.get("method_name")
    arguments = obj.get("argument_types")
    if (not isinstance(class_name, str) or len(class_name) > 256
            or not CLASS_NAME.fullmatch(class_name)
            or not class_name.startswith(package + ".")):
        raise ValueError("Class outside the authorized package prefix")
    if not isinstance(method_name, str) or not IDENTIFIER.fullmatch(method_name):
        raise ValueError("Method must be a single exact identifier")
    if (not isinstance(arguments, list) or len(arguments) > 16
            or any(not isinstance(x, str) or not x or len(x) > 160 for x in arguments)):
        raise ValueError("Explicit overload argument types are required")
    if obj.get("enabled", True) not in (True, False):
        raise ValueError("enabled must be boolean")
    return {
        "class_name": class_name,
        "method_name": method_name,
        "argument_types": arguments,
        "enabled": obj.get("enabled", True),
    }


def read_rules(path: Path, package: str) -> list[dict]:
    if path.stat().st_size > 64 * 1024:
        raise ValueError("Rules file exceeds 64 KiB")
    obj = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(obj, dict) or obj.get("schema_version") != 1:
        raise ValueError("Expected rules schema_version=1")
    if obj.get("package_name") != package:
        raise ValueError("Rules package does not match selected target")
    rules = obj.get("rules")
    if not isinstance(rules, list) or len(rules) > 16:
        raise ValueError("Rules must be a list of no more than 16")
    validated = [validate_rule(r, package) for r in rules]
    keys = [(r["class_name"], r["method_name"], tuple(r["argument_types"])) for r in validated]
    if len(set(keys)) != len(keys):
        raise ValueError("Duplicate Hook rules")
    return validated


def write_rules(path: Path, package: str, rules: list[dict]) -> None:
    if len(rules) > 16:
        raise ValueError("Too many hooks")
    validated = [validate_rule(rule, package) for rule in rules]
    payload = {"schema_version": 1, "package_name": package, "rules": validated}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
