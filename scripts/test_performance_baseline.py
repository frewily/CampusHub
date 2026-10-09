"""Offline regression for publication gates; no Docker or network operations."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import hashlib
import subprocess

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("run-performance-baseline.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
prep_spec = importlib.util.spec_from_file_location("native_k6_prep", Path(__file__).with_name("prepare-native-k6.py"))
prep = importlib.util.module_from_spec(prep_spec)
prep_spec.loader.exec_module(prep)


def summary():
    s = {"metrics": {"http_reqs": {"count": 100, "rate": 5},
        "checks": {"value": 1, "fails": 0, "passes": 400},
        "http_req_failed": {"value": 0}, "business_errors": {"count": 0},
        "iterations": {"count": 100},
        "http_req_duration": {"avg": 2, "min": 1, "med": 2, "max": 7, "p(90)": 3, "p(95)": 4, "p(99)": 6}}}
    for name in ("blocked", "connecting", "tls_handshaking", "sending", "waiting", "receiving"):
        s["metrics"]["http_req_" + name] = copy.deepcopy(s["metrics"]["http_req_duration"])
    return s


def runs():
    return [{"case": case, "vus": vus, "repeat": repeat, "measured": b.validate_summary(summary(), 4)}
            for case in b.CASES for vus in (1, 10) for repeat in (1, 2, 3)]


class SummaryTests(unittest.TestCase):
    def test_native_flat_schema(self):
        self.assertEqual(100, b.validate_summary(summary(), 4)["requests"])

    def test_nested_values_schema(self):
        s = summary()
        s["metrics"] = {key: {"values": value} for key, value in s["metrics"].items()}
        s["metrics"]["checks"]["values"]["rate"] = 1
        s["metrics"]["http_req_failed"]["values"]["rate"] = 0
        self.assertEqual(400, b.validate_summary(s, 4)["checks_passed"])

    def test_missing_metrics_are_not_zero(self):
        for key in summary()["metrics"]:
            s = summary(); del s["metrics"][key]
            with self.subTest(key=key), self.assertRaises(KeyError):
                b.validate_summary(s, 4)

    def test_invalid_numeric_metrics(self):
        for bad in (True, -1, float("nan"), float("inf"), "100", None, 0, 1.5):
            s = summary(); s["metrics"]["http_reqs"]["count"] = bad
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                b.validate_summary(s, 4)

    def test_response_failure_and_incomplete_iterations_rejected(self):
        for metric, field, value in (("checks", "value", .9), ("checks", "fails", 1),
                ("checks", "passes", 0), ("checks", "passes", 399),
                ("http_req_failed", "value", .01), ("business_errors", "count", 1),
                ("iterations", "count", 99), ("http_reqs", "rate", 0)):
            s = summary(); s["metrics"][metric][field] = value
            with self.subTest(metric=metric, field=field), self.assertRaises(ValueError):
                b.validate_summary(s, 4)

    def test_invalid_quantiles_rejected(self):
        s = summary(); s["metrics"]["http_req_duration"]["p(99)"] = 3
        with self.assertRaises(ValueError): b.validate_summary(s, 4)

    def test_average_and_p90_bounds_rejected(self):
        for field, bad in (("avg", 0), ("avg", 8), ("p(90)", 1), ("p(90)", 5)):
            s = summary(); s["metrics"]["http_req_duration"][field] = bad
            with self.subTest(field=field, bad=bad), self.assertRaises(ValueError):
                b.validate_summary(s, 4)

    def test_nonfinite_and_negative_latency_rejected(self):
        for field in summary()["metrics"]["http_req_duration"]:
            for bad in (float("nan"), float("inf"), -1, True, "2"):
                s = summary(); s["metrics"]["http_req_duration"][field] = bad
                with self.subTest(field=field, bad=bad), self.assertRaises(ValueError):
                    b.validate_summary(s, 4)

    def test_invalid_timing_components_rejected_even_if_total_is_positive(self):
        for name in ("blocked", "connecting", "tls_handshaking", "sending", "waiting", "receiving", "duration{expected_response:true}"):
            for bad in (-0.123973, float("nan"), float("inf")):
                s = summary()
                key = "http_req_" + name
                s["metrics"][key] = copy.deepcopy(s["metrics"]["http_req_duration"])
                s["metrics"][key]["min"] = bad
                with self.subTest(metric=key, bad=bad), self.assertRaises(ValueError):
                    b.validate_summary(s, 4)

    def test_aggregate_is_median_of_per_run_values(self):
        data = runs()
        for row, rate in zip(data[:3], (1, 9, 2)):
            row["measured"]["requests_per_second"] = rate
        self.assertEqual({"median": 2, "min": 1, "max": 9}, b.aggregate(data)[0]["requests_per_second"])

    def test_incomplete_duplicate_or_unknown_runs_rejected(self):
        data = runs()
        duplicate = copy.deepcopy(data); duplicate[0]["repeat"] = 2
        unknown = copy.deepcopy(data); unknown[0]["case"] = "other"
        for bad in (data[:-1], data + [data[0]], duplicate, unknown):
            with self.assertRaises(ValueError): b.aggregate(bad)


class NativeTests(unittest.TestCase):
    @patch.object(b.platform, "system", return_value="Darwin")
    @patch.object(b.platform, "machine", return_value="arm64")
    def test_binary_requires_absolute_path_and_pinned_hash(self, _machine, _system):
        with patch.dict(b.os.environ, {}, clear=True), self.assertRaises(ValueError): b.native_binary()
        with patch.dict(b.os.environ, {"K6_BINARY": "relative-k6"}, clear=True), self.assertRaises(ValueError): b.native_binary()
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "k6"; fake.write_bytes(b"not-the-official-binary")
            with patch.dict(b.os.environ, {"K6_BINARY": str(fake)}, clear=True), self.assertRaises(ValueError): b.native_binary()

    def test_native_child_uses_only_controlled_environment(self):
        with patch.dict(b.os.environ, {"K6_CLOUD_TOKEN": "synthetic", "HTTP_PROXY": "synthetic"}, clear=True):
            with patch.object(b.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
                b.native_run(["synthetic-test-binary", "version"])
        self.assertEqual(b.NATIVE_ENV, run.call_args.kwargs["env"])
        self.assertNotIn("K6_CLOUD_TOKEN", run.call_args.kwargs["env"])
        self.assertNotIn("HTTP_PROXY", run.call_args.kwargs["env"])
        self.assertEqual(50, run.call_args.kwargs["timeout"])
        self.assertEqual(b.a.ARTIFACTS, run.call_args.kwargs["cwd"])

    def test_native_failure_or_timeout_does_not_become_success(self):
        with patch.object(b.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaises(RuntimeError): b.native_run(["synthetic-test-binary"])
        with patch.object(b.subprocess, "run", side_effect=subprocess.TimeoutExpired([], 50)):
            with self.assertRaises(subprocess.TimeoutExpired): b.native_run(["synthetic-test-binary"])

    def test_archive_checksum_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "bad.zip"; archive.write_bytes(b"not-a-verified-archive")
            target = Path(folder) / "k6"
            with self.assertRaises(ValueError): prep.extract_verified(archive, target)
            self.assertFalse(target.exists())

    def test_extract_only_known_member_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "test.zip"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("k6-v1.3.0-macos-arm64/k6", b"synthetic-k6")
                out.writestr("../never-extract", b"synthetic")
            with patch.object(prep, "ARCHIVE_SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()):
                target = Path(folder) / "k6"
                prep.extract_verified(archive, target)
                self.assertEqual(b"synthetic-k6", target.read_bytes())
                self.assertEqual(0o700, target.stat().st_mode & 0o777)
                self.assertFalse((Path(folder).parent / "never-extract").exists())
                with self.assertRaises(FileExistsError): prep.extract_verified(archive, target)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="campushub-baseline-unit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "raw.json"
        self.source.write_bytes(b'{"metrics": {}}\r\n')
        self.name = "shop_detail_warm-vu1-r1.json"
        self.files = {self.name: self.source}
        self.report = {"cleanup_verified": True, "files_sha256": {self.name: b.digest(self.source)}}
        self.destination = self.root / "public"

    def test_raw_bytes_and_hashes_preserved(self):
        b.publish(self.report, self.files, self.destination)
        self.assertEqual(self.source.read_bytes(), (self.destination / self.name).read_bytes())
        self.assertEqual(self.report, json.loads((self.destination / "manifest.json").read_text()))

    def test_no_overwrite(self):
        self.destination.mkdir()
        with self.assertRaises(FileExistsError): b.publish(self.report, self.files, self.destination)

    def test_cleanup_gate(self):
        self.report["cleanup_verified"] = False
        with self.assertRaises(ValueError): b.publish(self.report, self.files, self.destination)
        self.assertFalse(self.destination.exists())

    def test_tampered_raw_export_rejected(self):
        self.source.write_bytes(b'{}')
        with self.assertRaises(ValueError): b.publish(self.report, self.files, self.destination)
        self.assertFalse(self.destination.exists())

    def test_private_fields_rejected_before_creating_directory(self):
        for marker in ("/Users/example", "/private/tmp/example", "/var/folders/example", "password", "AUTHORIZATION", "phone", "token", "session", "cookie"):
            self.report["unsafe"] = marker
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                b.publish(self.report, self.files, self.destination)
            self.assertFalse(self.destination.exists())

    def test_private_raw_export_rejected(self):
        self.source.write_bytes(b'{"password":"synthetic"}')
        self.report["files_sha256"][self.name] = b.digest(self.source)
        with self.assertRaises(ValueError): b.publish(self.report, self.files, self.destination)

    def test_path_traversal_rejected(self):
        self.report["files_sha256"]["../bad.json"] = b.digest(self.source)
        with self.assertRaises(ValueError): b.publish(self.report, {"../bad.json": self.source}, self.destination)


if __name__ == "__main__":
    unittest.main()
