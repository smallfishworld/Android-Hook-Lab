import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from trace_analyzer import call_tree, load_events, percentile, summarize, to_chrome_trace
from hooklab import make_parser, validate_args


def event(call, parent, duration, method="Example.go()", outcome="ok", tid=22):
    return {
        "schema_version": 1, "session_id": "s1", "engine": "FRIDA",
        "call_id": call, "parent_call_id": parent, "method": method,
        "outcome": outcome, "pid": 100, "tid": tid,
        "duration_ns": duration, "monotonic_time_ns": 200000,
    }


class TraceAnalysisTests(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(percentile(list(range(100)), 0.95), 94)
        self.assertEqual(percentile([], 0.95), 0)

    def test_stats(self):
        data = [event("a", None, 1000000),
                event("b", "a", 4000000, outcome="throw")]
        report = summarize(data)
        self.assertEqual(report["total_events"], 2)
        self.assertEqual(report["methods"][0]["errors"], 1)
        self.assertEqual(report["methods"][0]["p95_ms"], 4.0)

    def test_tree_is_explicit(self):
        data = [event("root", None, 800), event("child", "root", 200),
                event("other_thread", "root", 100, tid=99)]
        tree = call_tree(data)
        self.assertEqual(len(tree), 2)
        self.assertEqual(tree[0]["children"][0]["call_id"], "child")

    def test_chrome_trace(self):
        trace = to_chrome_trace([event("a", None, 1300)])
        self.assertEqual(trace["traceEvents"][0]["ph"], "X")
        self.assertEqual(trace["traceEvents"][0]["dur"], 1.3)

    def test_invalid_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            path.write_text(json.dumps({"schema_version": 999}) + "\n")
            with self.assertRaises(ValueError):
                load_events(path)

    def test_requires_explicit_authorization(self):
        parser = make_parser()
        opts = parser.parse_args(["--package", "com.example.external"])
        with self.assertRaisesRegex(ValueError, "authorized"):
            validate_args(opts)


if __name__ == "__main__":
    unittest.main()
