#!/usr/bin/env python3
"""Offline Frida JSONL trace statistics and Chrome Trace Event conversion."""
from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path


def load_events(path: Path, max_records: int = 1_000_000) -> list[dict]:
    output = []
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            if not line.strip():
                continue
            if len(line) > 128 * 1024:
                raise ValueError(f"Line {line_number} too long")
            item = json.loads(line)
            if not isinstance(item, dict) or item.get("schema_version") != 1:
                raise ValueError(f"Line {line_number}: unsupported event schema")
            if len(output) >= max_records:
                raise ValueError(f"Trace exceeds max_records={max_records}")
            output.append(item)
    return output


def percentile(data: list[int], q: float) -> int:
    if not data:
        return 0
    if not (0.0 < q <= 1.0):
        raise ValueError("Invalid percentile")
    values = sorted(data)
    return values[max(0, math.ceil(len(values) * q) - 1)]


def summarize(events: list[dict]) -> dict:
    methods = defaultdict(list)
    errors = Counter()
    for item in events:
        duration = item.get("duration_ns")
        if not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration < 0:
            continue
        method = str(item.get("method", "?"))[:300]
        methods[method].append(int(duration))
        if item.get("outcome") == "throw":
            errors[method] += 1
    rows = []
    for method, durations in methods.items():
        rows.append({
            "method": method, "count": len(durations), "errors": errors[method],
            "p50_ms": round(percentile(durations, 0.50) / 1e6, 4),
            "p95_ms": round(percentile(durations, 0.95) / 1e6, 4),
            "p99_ms": round(percentile(durations, 0.99) / 1e6, 4),
            "total_ms": round(sum(durations) / 1e6, 4),
        })
    rows.sort(key=lambda r: (-r["total_ms"], r["method"]))
    return {"total_events": len(events),
            "captured_method_events": sum(len(d) for d in methods.values()),
            "methods": rows}


def to_chrome_trace(events: list[dict]) -> dict:
    """Chrome Trace X events; only events with finite, valid monotonic timing."""
    trace = []
    for item in events:
        duration = item.get("duration_ns")
        time_ns = item.get("monotonic_time_ns")
        if (not isinstance(time_ns, (int, float))
                or not isinstance(duration, (int, float))
                or not math.isfinite(time_ns) or not math.isfinite(duration)
                or time_ns < 0 or duration < 0):
            continue
        trace.append({
            "name": str(item.get("method", "?"))[:300],
            "cat": "hooklab.frida",
            "ph": "X",
            "ts": time_ns / 1000,
            "dur": duration / 1000,
            "pid": int(item.get("pid", 0)),
            "tid": int(item.get("tid", 0)),
            "args": {
                "engine": item.get("engine", "FRIDA"),
                "outcome": item.get("outcome", "unknown"),
                "hook_id": item.get("hook_id", ""),
                "call_id": item.get("call_id", ""),
                "parent_call_id": item.get("parent_call_id"),
            },
        })
    return {"traceEvents": trace, "displayTimeUnit": "ms"}


def call_tree(events: list[dict]) -> list[dict]:
    """Produces explicit parent-child relations, without guessing from proximity."""
    nodes = {}
    ordered = []
    for item in events:
        key = (item.get("session_id"), item.get("pid"), item.get("call_id"))
        if not key[2]:
            continue
        node = {"call_id": key[2], "parent_call_id": item.get("parent_call_id"),
                "method": item.get("method"), "tid": item.get("tid"),
                "duration_ns": item.get("duration_ns"), "children": []}
        nodes[key] = node
        ordered.append((key, node))
    roots = []
    for key, node in ordered:
        parent_key = (key[0], key[1], node["parent_call_id"])
        parent = nodes.get(parent_key)
        if parent and parent is not node and parent["tid"] == node["tid"]:
            parent["children"].append(node)
        else:
            roots.append(node)
    return roots


def main() -> None:
    parser = argparse.ArgumentParser(description="Android Hook Lab offline M2 trace analyzer")
    parser.add_argument("input", type=Path, help="Frida JSONL trace file")
    parser.add_argument("--chrome", type=Path, help="export Chrome/Perfetto trace event JSON")
    parser.add_argument("--tree", type=Path, help="export explicit call nesting JSON")
    parser.add_argument("--summary", type=Path, help="write stats JSON to file")
    opts = parser.parse_args()
    events = load_events(opts.input)
    report = summarize(events)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if opts.chrome:
        opts.chrome.write_text(json.dumps(to_chrome_trace(events), ensure_ascii=False), encoding="utf-8")
        print(f"Chrome Trace written to {opts.chrome}")
    if opts.tree:
        opts.tree.write_text(json.dumps(call_tree(events), ensure_ascii=False), encoding="utf-8")
        print(f"Call tree written to {opts.tree}")
    if opts.summary:
        opts.summary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
