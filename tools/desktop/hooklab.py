#!/usr/bin/env python3
"""Android Hook Lab interactive Frida host (authorized applications only).

Attach to an already running Android application over the official Frida USB
device transport. This tool never starts an open network listener or shell.
"""
from __future__ import annotations

import argparse
import cmd
import json
from pathlib import Path
import queue
import shlex
import sys
import threading
import uuid

from rules import read_rules, write_rules

DEMO_PACKAGE = "dev.smallfish.hooklab.demo"


class TraceRecorder:
    """Bounded asynchronous JSONL recorder; Frida message callbacks never do disk I/O."""

    def __init__(self, destination: Path, capacity: int = 8192):
        self.destination = destination
        self.queue: queue.Queue[dict | None] = queue.Queue(maxsize=capacity)
        self.dropped = 0
        self.written = 0
        self._writer = threading.Thread(target=self._drain, daemon=True, name="trace-writer")
        self._writer.start()

    def record(self, event: dict) -> None:
        try:
            self.queue.put_nowait(event)
        except queue.Full:
            self.dropped += 1

    def _drain(self) -> None:
        self.destination.parent.mkdir(parents=True, exist_ok=True)
        with self.destination.open("w", encoding="utf-8") as file:
            while True:
                event = self.queue.get()
                if event is None:
                    self.queue.task_done()
                    break
                file.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
                self.written += 1
                self.queue.task_done()

    def close(self) -> None:
        self.queue.join()
        self.queue.put(None)
        self._writer.join(timeout=10)


class HookConsole(cmd.Cmd):
    prompt = "hooklab> "
    intro = ("Android Hook Lab M2: dynamic Java Hook (Frida 17)\n"
             "Type 'help' for commands; always stay within your testing authorization.")

    def __init__(self, exports, recorder: TraceRecorder, package: str):
        super().__init__()
        self.exports = exports
        self.recorder = recorder
        self.latest_error = ""
        self.package = package
        self.active_rules: dict[str, dict] = {}

    def _call(self, name: str, *args):
        try:
            return getattr(self.exports, name)(*args)
        except Exception as ex:
            self.latest_error = str(ex)
            print(f"[error] {ex}")
            return None

    def do_classes(self, line: str) -> None:
        """classes [substring] -- show up to 80 already loaded classes in the authorized package"""
        classes = self._call("discover", line.strip(), 80)
        for item in classes or []:
            print(item)
        if classes is not None:
            print(f"[{len(classes)} class(es)]")

    def do_methods(self, line: str) -> None:
        """methods <fully.qualified.Class> -- enumerate class overload signatures"""
        target = line.strip()
        if not target:
            print("Usage: methods dev.smallfish.hooklab.demo.ImageProcessor")
            return
        methods = self._call("methods", target)
        for entry in methods or []:
            types = ",".join(entry["arguments"])
            print(f"  {entry['name']}({types}) -> {entry['returns']}")

    def do_hook(self, line: str) -> None:
        """hook <full.Class> <method> [argument-type ...] -- install a specific Java overload"""
        try:
            parts = shlex.split(line)
        except ValueError as ex:
            print(f"Bad arguments: {ex}")
            return
        if len(parts) < 2:
            print("Usage: hook dev.smallfish.hooklab.demo.ImageProcessor adjustBrightness float int")
            return
        out = self._call("install", parts[0], parts[1], parts[2:])
        if out:
            self.active_rules[out["id"]] = {
                "class_name": parts[0], "method_name": parts[1],
                "argument_types": parts[2:], "enabled": True,
            }
            print(f"Hook registered: {out['id']}  {out['method']}")

    def do_unhook(self, line: str) -> None:
        """unhook <F#> -- detach a selected interceptor"""
        if self._call("remove", line.strip()):
            self.active_rules.pop(line.strip(), None)
            print("Unhooked.")

    def do_loadrules(self, line: str) -> None:
        """loadrules <rules.json> -- install up to 16 exact, allowlisted Hook rules"""
        file = Path(line.strip())
        if not line.strip():
            print("Usage: loadrules rules.json")
            return
        try:
            rules = read_rules(file, self.package)
        except (OSError, ValueError) as ex:
            print(f"[invalid rules] {ex}")
            return
        for rule in rules:
            if not rule["enabled"]:
                continue
            if len(self.active_rules) >= 16:
                print("[limit] Maximum 16 simultaneous hooks")
                break
            result = self._call("install", rule["class_name"], rule["method_name"], rule["argument_types"])
            if result:
                self.active_rules[result["id"]] = rule
                print(f"Installed {result['id']}: {result['method']}")

    def do_saverules(self, line: str) -> None:
        """saverules <rules.json> -- save current exact Hook declarations (no secrets)"""
        if not line.strip():
            print("Usage: saverules rules.json")
            return
        try:
            write_rules(Path(line.strip()), self.package, list(self.active_rules.values()))
            print("Saved " + str(len(self.active_rules)) + " Hook rules")
        except (OSError, ValueError) as ex:
            print(f"[save failed] {ex}")

    def do_status(self, _line: str) -> None:
        """status -- list active hooks and dropped event counters"""
        status = self._call("status")
        if status is not None:
            print(json.dumps(status, indent=2, ensure_ascii=False))
            print(f"Host stored={self.recorder.written} dropped={self.recorder.dropped}")
            print(f"Trace file: {self.recorder.destination}")

    def do_exit(self, _line: str) -> bool:
        """exit -- stop hooks, flush event log and disconnect"""
        return True

    def do_EOF(self, _line: str) -> bool:
        print()
        return True

    do_quit = do_exit

    def emptyline(self) -> None:
        pass


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Android Hook Lab M2 interactive Java Hook debugger")
    parser.add_argument("--package", default=DEMO_PACKAGE, help="exact Android package to attach")
    parser.add_argument("--prefix", default=None, help="class prefix; defaults to --package")
    parser.add_argument("--authorized", action="store_true",
                        help="explicitly confirm authorization for a non-Demo package")
    parser.add_argument("--rules", type=Path, help="load validated exact-method rules on startup")
    parser.add_argument("--stack", action="store_true",
                        help="capture up to 12 class/method-only app stack frames")
    parser.add_argument("--max-events", type=int, default=200,
                        help="max sampled event messages per second (1-1000)")
    parser.add_argument("--output", type=Path, default=Path("trace/m2-frida.jsonl"))
    parser.add_argument("--agent", type=Path, default=Path(__file__).resolve().parents[2]
                        / "engines/frida/dist/agent.bundle.js")
    return parser


def validate_args(args) -> None:
    if args.package != DEMO_PACKAGE and not args.authorized:
        raise ValueError("Non-Demo target requires --authorized and explicit testing permission")
    if not args.package or not args.package.replace("_", "").replace(".", "").isalnum():
        raise ValueError("Invalid package name")
    if args.prefix is None:
        args.prefix = args.package
    if args.prefix != args.package and not args.prefix.startswith(args.package + "."):
        raise ValueError("Class prefix must stay within the explicitly chosen package")
    if not (1 <= args.max_events <= 1000):
        raise ValueError("--max-events must be between 1 and 1000")
    if not args.agent.is_file():
        raise ValueError(f"Missing compiled Frida agent: {args.agent}; run npm ci && npm run build in engines/frida")


def main(argv: list[str] | None = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    try:
        validate_args(args)
    except ValueError as ex:
        parser.error(str(ex))
    try:
        import frida  # Lazy import: unit tests and trace analyzer need no device/dependency.
    except ImportError:
        parser.error("Missing frida Python package: python -m pip install -r tools/desktop/requirements.txt")
    recorder = TraceRecorder(args.output)
    session = None
    script = None
    try:
        device = frida.get_usb_device(timeout=8)
        session = device.attach(args.package)
        script = session.create_script(args.agent.read_text(encoding="utf-8"))

        def on_message(message, _binary):
            if message.get("type") == "send":
                payload = message.get("payload", {})
                if payload.get("kind") == "hook_event":
                    event = payload.get("event")
                    if isinstance(event, dict) and event.get("schema_version") == 1:
                        recorder.record(event)
            elif message.get("type") == "error":
                print("[agent-error] " + str(message.get("description", "unknown")), file=sys.stderr)

        script.on("message", on_message)
        script.load()
        config = script.exports_sync.configure({
            "package_prefix": args.prefix,
            "session_id": str(uuid.uuid4()),
            "capture_stack": args.stack,
            "max_events_per_sec": args.max_events,
        })
        print(f"Connected to {args.package}; {json.dumps(config)}")
        print("Try: classes ImageProcessor | methods dev.smallfish.hooklab.demo.ImageProcessor")
        print("Then: hook dev.smallfish.hooklab.demo.ImageProcessor adjustBrightness float int")
        console = HookConsole(script.exports_sync, recorder, args.package)
        if args.rules:
            console.do_loadrules(str(args.rules))
        console.cmdloop()
    except KeyboardInterrupt:
        print("\nInterrupted; stopping the tracing session.")
    except Exception as ex:
        print(f"Failed: {ex}", file=sys.stderr)
        return 1
    finally:
        if script is not None:
            try:
                script.exports_sync.stop()
            except Exception:
                pass
            try:
                script.unload()
            except Exception:
                pass
        if session is not None:
            try:
                session.detach()
            except Exception:
                pass
        recorder.close()
        print(f"Saved {recorder.written} events to {args.output} ({recorder.dropped} host-side dropped)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
