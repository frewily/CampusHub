"""Offline evidence gates. No HTTP, Docker, or real user credentials."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("flash_baseline", Path(__file__).with_name("run-flash-sale-baseline.py"))
f = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)


def fixture(n=20):
    return {"version": 1, "activityId": "123", "actors": [
        {"id": str(i + 1), "token": "%032x" % (i + 1)} for i in range(n)]}


def summary(n=20):
    trend = {"avg": 2, "min": 1, "med": 2, "p(90)": 3, "p(95)": 4, "p(99)": 5, "max": 6}
    result = {"metrics": {"http_reqs": {"count": 2 * n, "rate": 10}, "iterations": {"count": n},
        "checks": {"value": 1, "passes": 6 * n, "fails": 0}, "http_req_failed": {"value": 0},
        "admission_new": {"count": n // 2}, "admission_replayed": {"count": n // 2},
        "admission_sold_out": {"count": n}, "unexpected_outcomes": {"count": 0}}}
    for name in ["http_req_" + part for part in (
        "duration", "blocked", "connecting", "tls_handshaking", "sending", "waiting", "receiving")
        ] + [name + "_ms" for name in f.OUTCOMES[:3]]:
        result["metrics"][name] = copy.deepcopy(trend)
    return result


def ledger():
    return dict(rows=[(str(100 + i), str(i), "123", "1") for i in range(1, 11)],
        accepted=[(str(i), str(100 + i)) for i in range(1, 11)],
        participants=[str(i) for i in range(1, 11)],
        events=[(str(100 + i), str(i), "123") for i in range(1, 11)],
        actor_ids=[str(i) for i in range(1, 21)], activity="123", stock=10, db_stock=0,
        redis_stock=0, pending=0, delivered="100-10", last_stream_id="100-10", dead=0,
        attempts=0, failure_index=0, cancellations=0)


def runs():
    return [{"repeat": repeat, "measured": f.validate_summary(summary(n), n),
        "ledger": {"invariants_verified": True}, "driver_load_wall_seconds": 2,
        "load_exit_to_all_orders_observed_seconds": .5}
        for n in f.COHORTS for repeat in (1, 2, 3)]


class FixtureTests(unittest.TestCase):
    def test_allowed_cohorts_and_lossless_long(self):
        for n in (10, 20, 200):
            self.assertEqual(n, len(f.validate_fixture(fixture(n), n)["actors"]))
        self.assertEqual("9223372036854775807", f.positive_id("9223372036854775807"))

    def test_invalid_ids(self):
        for value in (0, True, None, "0", "01", "-1", "1e3", "9223372036854775808", "1" * 30):
            with self.subTest(value=value), self.assertRaises(ValueError): f.positive_id(value)

    def test_duplicate_identity_or_credential(self):
        for key in ("id", "token"):
            data = fixture(); data["actors"][1][key] = data["actors"][0][key]
            with self.subTest(key=key), self.assertRaises(ValueError): f.validate_fixture(data, 20)

    def test_invalid_fixture_schema_population_and_credential(self):
        for mutate in (lambda d: d.update(version=True), lambda d: d.update(extra=1),
                       lambda d: d["actors"].pop(), lambda d: d["actors"][0].update(token="invalid")):
            data = fixture(); mutate(data)
            with self.assertRaises(ValueError): f.validate_fixture(data, 20)
        for n in (1, 19, 201, True):
            with self.assertRaises(ValueError): f.validate_fixture(fixture(), n)


class SummaryTests(unittest.TestCase):
    def test_exact_fixed_batch(self):
        for n in (10, 20, 200):
            self.assertEqual(2 * n, f.validate_summary(summary(n), n)["requests"])

    def test_every_required_metric(self):
        for name in summary()["metrics"]:
            data = summary(); del data["metrics"][name]
            with self.subTest(name=name), self.assertRaises(KeyError): f.validate_summary(data, 20)

    def test_expected_sold_out_is_not_unexpected_error(self):
        data = summary(); self.assertEqual(20, f.validate_summary(data, 20)["outcomes"]["admission_sold_out"])
        data["metrics"]["http_req_failed"]["value"] = .5
        with self.assertRaises(ValueError): f.validate_summary(data, 20)

    def test_extra_partial_or_wrong_outcome(self):
        for name, field in (("http_reqs", "count"), ("iterations", "count"), ("checks", "passes"),
                            ("admission_new", "count"), ("admission_replayed", "count"),
                            ("admission_sold_out", "count"), ("unexpected_outcomes", "count")):
            data = summary(); data["metrics"][name][field] += 1
            with self.subTest(name=name), self.assertRaises(ValueError): f.validate_summary(data, 20)

    def test_all_timing_series_checked(self):
        for name, values in summary()["metrics"].items():
            if "min" not in values: continue
            for bad in (-.1, float("nan"), float("inf"), True):
                data = summary(); data["metrics"][name]["min"] = bad
                with self.subTest(name=name, bad=bad), self.assertRaises(ValueError): f.validate_summary(data, 20)
        data = summary(); data["metrics"]["http_req_sending{expected_response:true}"] = copy.deepcopy(
            data["metrics"]["http_req_sending"])
        data["metrics"]["http_req_sending{expected_response:true}"]["min"] = -.01
        with self.assertRaises(ValueError): f.validate_summary(data, 20)

    def test_nested_metric_schema(self):
        data = summary(); data["metrics"] = {name: {"values": values} for name, values in data["metrics"].items()}
        self.assertEqual(40, f.validate_summary(data, 20)["requests"])


class LedgerTests(unittest.TestCase):
    def test_redis_62_structured_read_preserves_string_ids_and_nested_arrays(self):
        data = [["1-0", ["id", "9223372036854775807", "userId", "1", "voucherId", "123"]]]
        with patch.object(f.a, "redis", return_value=json.dumps(data)) as read:
            self.assertEqual(data, f.redis_json("XRANGE", "stream.orders", "-", "+"))
            read.assert_called_once_with("EVAL", "return cjson.encode(redis.call(unpack(ARGV)))", "0",
                                         "XRANGE", "stream.orders", "-", "+")
        for command in (("SET", "x", "1"), ("XINFO", "CONSUMERS", "stream.orders", "g1"), ()):
            with self.subTest(command=command), self.assertRaises(ValueError): f.redis_json(*command)

    def test_cli_integer_text_is_parsed_at_command_boundary(self):
        for value, expected in (("0", 0), ("10", 10), ("100", 100)):
            self.assertEqual(expected, f.cli_integer(value))
        for bad in ("", "-1", "01", "1.0", "NaN", "1\n2", " 1", "1 ", True, 1, None):
            with self.subTest(value=bad), self.assertRaises(ValueError): f.cli_integer(bad)
        with self.assertRaises(ValueError): f.cli_integer("0", 1)
        # Numeric JSON metrics stay strict; CLI parsing must not broaden their contract.
        with self.assertRaises(ValueError): f.integer("0")

    def test_full_mapping_not_just_counts(self):
        self.assertTrue(f.validate_ledger(**ledger())["invariants_verified"])
        for name, bad in (("rows", [("101", "1", "123", "1")] * 10),
                ("accepted", [(str(i), "999") for i in range(1, 11)]),
                ("participants", ["1"] * 10), ("events", [("101", "1", "123")] * 10)):
            data = ledger(); data[name] = bad
            with self.subTest(name=name), self.assertRaises(ValueError): f.validate_ledger(**data)

    def test_stock_pending_failure_markers_rejected(self):
        for name in ("db_stock", "redis_stock", "pending", "dead", "attempts", "failure_index", "cancellations"):
            data = ledger(); data[name] = 1
            with self.subTest(name=name), self.assertRaises(ValueError): f.validate_ledger(**data)
        data = ledger(); data["delivered"] = "100-9"
        with self.assertRaises(ValueError): f.validate_ledger(**data)

    def test_foreign_user_or_wrong_order_state(self):
        data = ledger(); data["rows"][0] = ("101", "1", "123", "2")
        with self.assertRaises(ValueError): f.validate_ledger(**data)
        data = ledger(); data["accepted"][0] = ("99999", "101")
        with self.assertRaises(ValueError): f.validate_ledger(**data)

    def test_strict_evidence_parsing(self):
        self.assertEqual([("1", "2")], f.parse_rows("1\t2", 2))
        with self.assertRaises(ValueError): f.parse_rows("1\t2\t3", 2)
        with self.assertRaises(ValueError): f.pairs(["1", "101", "orphan"])

    def test_http_witness_ids_must_match_accepted_mapping(self):
        accepted = ledger()["accepted"]
        witness = [{"userId": str(i), "orderId": str(100 + i) if i <= 10 else None,
                    "outcome": "accepted" if i <= 10 else "sold_out"} for i in range(1, 21)]
        f.validate_witness(witness, ledger()["actor_ids"], accepted)
        witness[0]["orderId"] = "999"
        with self.assertRaises(ValueError): f.validate_witness(witness, ledger()["actor_ids"], accepted)

    def test_duplicate_or_missing_witness_rejected(self):
        witness = [{"userId": str(i), "orderId": str(100 + i) if i <= 10 else None,
                    "outcome": "accepted" if i <= 10 else "sold_out"} for i in range(1, 21)]
        for bad in (witness[:-1], witness + [witness[0]], [witness[0]] * 20):
            with self.assertRaises(ValueError): f.validate_witness(bad, ledger()["actor_ids"], ledger()["accepted"])

    def test_private_console_witness_parsing(self):
        message = {"userId": "1", "orderId": "101", "outcome": "accepted"}
        self.assertEqual([message], f.parse_witness_log("progress\nCOHORT_WITNESS " + json.dumps(message)))


class PublicationTests(unittest.TestCase):
    def test_only_native_empty_root_group_digest_is_public(self):
        data = summary()
        data["root_group"] = {"name": "", "path": "", "id": "d41d8cd98f00b204e9800998ecf8427e",
                              "groups": {}, "checks": {}}
        f.validate_raw_privacy(json.dumps(data).encode())
        for mutate in (lambda d: d.update(extra="d41d8cd98f00b204e9800998ecf8427e"),
                       lambda d: d["root_group"].update(id="a" * 32),
                       lambda d: d["root_group"].update(path="unexpected"),
                       lambda d: d.update(extra="a" * 32)):
            bad = copy.deepcopy(data); mutate(bad)
            with self.assertRaises(ValueError): f.validate_raw_privacy(json.dumps(bad).encode())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="campushub-flash-unit-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.files = {}; data = runs()
        for row in data:
            n = row["measured"]["actors"]; repeat = row["repeat"]
            row["ledger"] = f.verified_ledger(n // 2)
            row["warmup"] = {"measured": f.validate_summary(summary(10), 10), "ledger": f.verified_ledger(5),
                "driver_load_wall_seconds": 1, "load_exit_to_all_orders_observed_seconds": .5}
            for suffix, population in (("", n), ("-warmup", 10)):
                name = "flash-sale-n%d-r%d%s.json" % (n, repeat, suffix)
                path = self.folder / name
                path.write_text(json.dumps(summary(population)))
                self.files[name] = path
        self.report = {"cleanup_verified": True,
            "scope": "finite synthetic flash-sale cohorts; not capacity/TPS/SLO/per-order commit latency",
            "runs": data, "aggregate": f.aggregate(data),
            "files_sha256": {name: f.b.digest(path) for name, path in self.files.items()}}
        self.destination = self.folder / "public"

    def test_preserves_all_twelve_raw_exports(self):
        f.publish(self.report, self.files, self.destination)
        for name, path in self.files.items():
            self.assertEqual(path.read_bytes(), (self.destination / name).read_bytes())

    def test_scope_cleanup_completeness_and_no_overwrite(self):
        for key, value in (("cleanup_verified", False), ("scope", "capacity")):
            report = copy.deepcopy(self.report); report[key] = value
            with self.assertRaises(ValueError): f.publish(report, self.files, self.destination)
        files = dict(self.files); files.pop(next(iter(files)))
        with self.assertRaises(ValueError): f.publish(self.report, files, self.destination)
        self.destination.mkdir()
        with self.assertRaises(FileExistsError): f.publish(self.report, self.files, self.destination)

    def test_raw_hash_and_formal_or_warmup_ledger_tampering_rejected(self):
        for mutate in (lambda r: r["files_sha256"].update({next(iter(self.files)): "wrong"}),
            lambda r: r["runs"][0]["ledger"].update(new_orders=0),
            lambda r: r["runs"][0]["warmup"]["ledger"].update(invariants_verified=False),
            lambda r: r["runs"][0]["measured"]["outcomes"].update(admission_new=0)):
            report = copy.deepcopy(self.report); mutate(report)
            with self.assertRaises(ValueError): f.publish(report, self.files, self.destination)
            self.assertFalse(self.destination.exists())

    def test_private_report_raw_and_nonfinite_observation_rejected(self):
        for marker in ("/Users/example", "authorization", "token", "phone", "password"):
            report = copy.deepcopy(self.report); report["unsafe"] = marker
            with self.assertRaises(ValueError): f.publish(report, self.files, self.destination)
        report = copy.deepcopy(self.report)
        report["runs"][0]["warmup"]["driver_load_wall_seconds"] = float("nan")
        with self.assertRaises(ValueError): f.publish(report, self.files, self.destination)
        name = next(iter(self.files)); path = self.files[name]
        data = summary(); data["unsafe"] = "a" * 32
        path.write_text(json.dumps(data)); self.report["files_sha256"][name] = f.b.digest(path)
        with self.assertRaises(ValueError): f.publish(self.report, self.files, self.destination)


class AggregateTests(unittest.TestCase):
    def test_per_run_quantiles_not_pooled(self):
        data = runs()
        for row, value in zip(data[:3], (1, 9, 2)):
            row["load_exit_to_all_orders_observed_seconds"] = value
        self.assertEqual(2, f.aggregate(data)[0]["load_exit_to_all_orders_observed_seconds"]["median"])

    def test_incomplete_duplicate_nonfinite(self):
        duplicate = runs(); duplicate[0]["repeat"] = 2
        nonfinite = runs(); nonfinite[0]["load_exit_to_all_orders_observed_seconds"] = float("nan")
        for data in (runs()[:-1], runs() + [runs()[0]], duplicate, nonfinite):
            with self.assertRaises(ValueError): f.aggregate(data)


class SafetyTests(unittest.TestCase):
    def test_pinned_flat_native_inspect_schema(self):
        data = {"scenarios": {"flash_sale": {"executor": "per-vu-iterations", "vus": 20,
            "iterations": 1, "maxDuration": "30s", "gracefulStop": "5s"}}, "maxRedirects": 0, "systemTags": []}
        f.validate_inspect(data, 20)
        data["systemTags"] = None  # Pinned native inspect serializes the empty tag set as null.
        f.validate_inspect(data, 20)
        with self.assertRaises(KeyError): f.validate_inspect({"options": data}, 20)

    def test_native_option_drift_rejected(self):
        data = {"scenarios": {"flash_sale": {"executor": "per-vu-iterations", "vus": 20,
            "iterations": 1, "maxDuration": "30s", "gracefulStop": "5s"}}, "maxRedirects": 0, "systemTags": []}
        data["systemTags"] = ["url"]
        with self.assertRaises(ValueError): f.validate_inspect(data, 20)

    def test_raw_token_header_regression(self):
        script = f.SCRIPT.read_text()
        self.assertNotIn("Bearer ", script)
        self.assertIn("COHORT_WITNESS ", script)
        self.assertIn('systemTags: []', script)

    def test_child_uses_pinned_private_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "owned"
            path = f.private_fixture(folder, fixture(), 20)
            args = f.native_args(Path("/synthetic/k6"), folder, path, 20, "12345", inspect=True)
            self.assertIn("inspect", args); self.assertIn("--include-system-env-vars=false", args)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(0o700, folder.stat().st_mode & 0o777)
            for port in ("0", "01", "65536", "http://example", "12a", "-1"):
                with self.subTest(port=port), self.assertRaises(ValueError):
                    f.native_args(Path("/synthetic/k6"), folder, path, 20, port)

    def test_no_redirect_or_inherited_proxy(self):
        with patch.object(f.urllib.request, "build_opener") as build:
            with self.assertRaises(Exception): f.owned_request("http://127.0.0.1:12345", "/x")
        self.assertTrue(any(isinstance(arg, f.urllib.request.ProxyHandler) and arg.proxies == {}
                            for arg in build.call_args.args))
        self.assertTrue(any(isinstance(arg, f.NoRedirect) for arg in build.call_args.args))
        with self.assertRaises(ValueError): f.owned_request("https://example.com", "/x")


if __name__ == "__main__":
    unittest.main()
