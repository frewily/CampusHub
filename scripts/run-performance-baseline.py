#!/usr/bin/env python3
"""Bounded local k6 baseline. Own the synthetic target; publish only validated aggregate exports."""
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import time
import uuid

spec = importlib.util.spec_from_file_location("monitoring_acceptance", Path(__file__).with_name("verify-observability.py"))
monitoring = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitoring)
a = monitoring.acceptance
a.PROJECT = "campushub-phase6c-test-" + uuid.uuid4().hex[:12]
a.COMPOSE[a.COMPOSE.index("-p") + 1] = a.PROJECT
a.COMPOSE[a.COMPOSE.index("-p"):a.COMPOSE.index("-p")] = ["-f", str(a.ROOT / "compose.performance.yaml")]
K6_SHA256 = "40fa9d8cb693a9bb8034810057a6eb3b45318e3258d1a11cdaa95d72f582ccb2"
K6_ARCHIVE_SHA256 = "eb06b22418e26f7394023e53aaaddf0bec739f669acf718cc9b0e2d7f12bd7be"
SCRIPT = a.ROOT / "scripts/load/baseline.js"
CASES = ("shop_detail_warm", "shop_search_mysql")
CACHE = ("first_hit", "first_negative_hit", "first_miss", "load_started", "publish_rejected", "unavailable_signal")
NATIVE_ENV = {"PATH": os.defpath, "LANG": "C", "TMPDIR": str(a.ARTIFACTS),
              "GOMAXPROCS": "1", "GOMEMLIMIT": "256MiB", "NO_PROXY": "127.0.0.1,localhost"}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(source.read()).hexdigest()


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError("expected finite nonnegative metric")
    return value


def metric_values(summary, name):
    entry = summary["metrics"][name]
    return entry.get("values", entry)


def timing_values(summary, name):
    latency = {key: number(metric_values(summary, name)[key])
               for key in ("avg", "min", "med", "max", "p(90)", "p(95)", "p(99)")}
    if not (latency["min"] <= latency["med"] <= latency["p(90)"] <= latency["p(95)"] <= latency["p(99)"] <= latency["max"]
            and latency["min"] <= latency["avg"] <= latency["max"]):
        raise ValueError("invalid latency quantiles: " + name)
    return latency


def validate_summary(summary, checks_per_request=None):
    requests = metric_values(summary, "http_reqs")
    count, rate = number(requests["count"]), number(requests["rate"])
    if count <= 0 or count != int(count) or rate <= 0:
        raise ValueError("no completed measured requests")
    checks = metric_values(summary, "checks")
    failed = metric_values(summary, "http_req_failed")
    if number(checks.get("rate", checks.get("value"))) != 1 or number(checks["fails"]) != 0:
        raise ValueError("response contract checks failed")
    passes = number(checks["passes"])
    if passes <= 0 or passes != int(passes) or (checks_per_request is not None and passes != checks_per_request * count):
        raise ValueError("missing response contract checks")
    if number(failed.get("rate", failed.get("value"))) != 0:
        raise ValueError("HTTP failures invalidate this baseline")
    if number(metric_values(summary, "business_errors")["count"]) != 0:
        raise ValueError("business failures invalidate this baseline")
    if number(metric_values(summary, "iterations")["count"]) != count:
        raise ValueError("incomplete or extra-request iterations")
    latency = timing_values(summary, "http_req_duration")
    for name in ("blocked", "connecting", "tls_handshaking", "sending", "waiting", "receiving"):
        timing_values(summary, "http_req_" + name)
    # Validate tagged timing series too: a positive total cannot conceal an invalid component.
    for name in summary["metrics"]:
        if name.startswith("http_req_") and "{" in name and "min" in metric_values(summary, name):
            timing_values(summary, name)
    return {"requests": int(count), "requests_per_second": rate, "http_req_duration_ms": latency,
            "http_error_rate": 0, "business_errors": 0, "checks_passed": int(passes)}


def aggregate(runs):
    if len(runs) != 12:
        raise ValueError("exactly twelve measured runs required")
    result = []
    for case in CASES:
        for vus in (1, 10):
            group = [run for run in runs if run["case"] == case and run["vus"] == vus]
            if sorted(run["repeat"] for run in group) != [1, 2, 3]:
                raise ValueError("exactly three independent sequential runs required per case/VU pair")
            row = {"case": case, "vus": vus, "repeats": 3}
            for label, values in {
                "requests_per_second": [run["measured"]["requests_per_second"] for run in group],
                "per_run_p95_ms": [run["measured"]["http_req_duration_ms"]["p(95)"] for run in group],
                "per_run_p99_ms": [run["measured"]["http_req_duration_ms"]["p(99)"] for run in group],
            }.items():
                row[label] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
            result.append(row)
    return result


def fresh_snapshot(prom, after):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        times = monitoring.query(prom, 'timestamp(up{job="campushub"})')
        if times and float(times[0]["value"][1]) > after and monitoring.query(prom, 'up{job="campushub"} == 1'):
            samples = monitoring.query(prom, 'campushub_shop_cache_events_total{job="campushub"}')
            counts = {sample["metric"]["outcome"]: float(sample["value"][1]) for sample in samples}
            if set(counts) == set(CACHE) and all(math.isfinite(value) and value >= 0 for value in counts.values()):
                return {"scrape_timestamp": float(times[0]["value"][1]), "cache": counts}
        time.sleep(0.5)
    raise RuntimeError("fresh successful business metric snapshot unavailable")


def native_binary():
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("the pinned native baseline requires macOS ARM64")
    value = os.environ.get("K6_BINARY", "")
    if not value or not Path(value).is_absolute():
        raise ValueError("K6_BINARY must be an absolute path to the prepared native executable")
    binary = Path(value).resolve(strict=True)
    if not binary.is_file() or digest(binary) != K6_SHA256:
        raise ValueError("native k6 executable checksum mismatch")
    return binary


def native_run(args, timeout=50):
    # No inherited proxy, K6_* output/cloud options, secrets, or personal config.
    log = a.ARTIFACTS / ("native-%s.log" % uuid.uuid4().hex)
    with log.open("xb") as output:
        log.chmod(0o600)
        # subprocess.run kills and waits for its direct k6 child on timeout.
        result = subprocess.run(args, cwd=a.ARTIFACTS, env=NATIVE_ENV, stdout=output,
                                stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError("native k6 failed; private diagnostic log: " + str(log))
    return log.read_text("utf-8", errors="replace").strip()


def trial(binary, target_port, case, vus, repeat, duration):
    label = "%s-vu%d-r%d-%s" % (case, vus, repeat, duration)
    folder = a.ARTIFACTS / label
    folder.mkdir(mode=0o700)
    config = folder / "empty-config.json"
    config.write_text("{}"); config.chmod(0o600)
    if digest(binary) != K6_SHA256:
        raise ValueError("native k6 executable changed before trial")
    before = time.time()
    native_run([str(binary), "--config", str(config), "--address", "127.0.0.1:0", "--no-color",
                "run", "--no-usage-report", "--include-system-env-vars=false", "--env", "CASE=" + case,
                "--env", "VUS=" + str(vus), "--env", "DURATION=" + duration, "--env", "TARGET_PORT=" + target_port,
                "--summary-export", str(folder / "summary.json"), str(SCRIPT)])
    end = time.time()
    path = folder / "summary.json"
    path.chmod(0o600)
    raw = json.loads(path.read_text())
    return path, validate_summary(raw, 4 if case == "shop_detail_warm" else 7), {"started_at_epoch": before, "finished_at_epoch": end}


def image_metadata(image):
    return json.loads(a.run(["docker", "image", "inspect", image, "--format",
                             '{"id":{{json .Id}},"repo_digests":{{json .RepoDigests}},"architecture":{{json .Architecture}}}']))


def publish(report, files, destination):
    if report.get("cleanup_verified") is not True:
        raise ValueError("cleanup must be verified before publication")
    encoded = json.dumps(report, indent=2, allow_nan=False).encode("utf-8")
    raw_exports = {name: path.read_bytes() for name, path in files.items()}
    for name, content in raw_exports.items():
        if not re.fullmatch(r"shop_(?:detail_warm|search_mysql)-vu(?:1|10)-r[123](?:-warmup)?\.json", name):
            raise ValueError("unexpected export filename")
        if hashlib.sha256(content).hexdigest() != report["files_sha256"][name]:
            raise ValueError("raw summary changed before publication")
    for content in [encoded, *raw_exports.values()]:
        if re.search(rb"/Users/|/private/tmp/|/var/folders/|(?i:authorization|password|token|phone|session|cookie)", content):
            raise ValueError("private field in public baseline export")
    destination.mkdir(parents=True, exist_ok=False)
    for name, content in raw_exports.items():
        (destination / name).write_bytes(content)
    (destination / "manifest.json").write_bytes(encoded)


def main():
    if a.ENV.get("DOCKER_HOST") and not a.ENV["DOCKER_HOST"].startswith("unix://"):
        raise RuntimeError("only a local Docker engine is allowed")
    assert a.run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"] ).startswith("unix://")
    jar = a.ROOT / "target/campushub-0.0.1-SNAPSHOT.jar"
    assert jar.is_file(), "build the Java 8 package first"
    # Runtime Java must be the recorded revision, not an uncommitted application experiment.
    a.run(["git", "diff", "--quiet", "HEAD", "--", "src/main", "pom.xml", "Dockerfile", "compose.yaml", "compose.monitoring.yaml"])
    assert a.compose("ps", "-aq") == ""
    assert a.run(["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + a.PROJECT,
                  "--format", "{{.Name}}"] ) == ""
    binary = native_binary()
    tool_version = native_run([str(binary), "version"])
    assert "k6 v1.3.0" in tool_version and "darwin/arm64" in tool_version
    metadata = {"application_revision": a.run(["git", "rev-parse", "HEAD"]), "application_jar_sha256": digest(jar),
                "k6": {"mode": "native", "version": tool_version, "binary_sha256": digest(binary),
                       "archive_sha256": K6_ARCHIVE_SHA256,
                       "release_url": "https://github.com/grafana/k6/releases/tag/v1.3.0"},
                "script_sha256": digest(SCRIPT), "overlay_sha256": digest(a.ROOT / "compose.performance.yaml"),
                "harness_sha256": digest(Path(__file__)),
                "support_scripts_sha256": {name: digest(a.ROOT / "scripts" / name)
                                           for name in ("verify-compose.py", "verify-observability.py", "prepare-native-k6.py")},
                "host": {"os": platform.system(), "os_version": platform.mac_ver()[0] if platform.system() == "Darwin" else platform.release(),
                         "architecture": platform.machine()},
                "docker": json.loads(a.run(["docker", "info", "--format",
                              '{"version":{{json .ServerVersion}},"architecture":{{json .Architecture}},"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}}}']))}
    if platform.system() == "Darwin":
        metadata["host"].update(model=a.run(["sysctl", "-n", "hw.model"]), cpu=a.run(["sysctl", "-n", "machdep.cpu.brand_string"]),
                                cpus=int(a.run(["sysctl", "-n", "hw.ncpu"])), memory_bytes=int(a.run(["sysctl", "-n", "hw.memsize"])))
    runs, files = [], {}
    try:
        a.compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=240)
        base = a.app_base()
        target_port = base.rsplit(":", 1)[1]
        port = a.compose("port", "app", "9090")
        assert re.fullmatch(r"127\.0\.0\.1:[0-9]+", port)
        prom = "http://" + port
        metadata["resources"] = {}
        for service, cpus, memory in (("app", 2, 768), ("mysql", 1, 768), ("redis", 0.5, 256), ("prometheus", 0.5, 256)):
            container = a.compose("ps", "-q", service)
            config = json.loads(a.run(["docker", "inspect", container, "--format",
                                      '{"nano_cpus":{{.HostConfig.NanoCpus}},"memory_bytes":{{.HostConfig.Memory}}}']))
            assert config == {"nano_cpus": int(cpus * 1_000_000_000), "memory_bytes": memory * 1024 * 1024}
            metadata["resources"][service] = config
        metadata["resources"]["k6_runner"] = {"mode": "native", "gomaxprocs": 1, "gomemlimit": "256MiB",
                                                "limits": "Go runtime settings only; no enforced OS CPU/memory/PID quotas"}
        metadata["application_jvm"] = a.compose("exec", "-T", "app", "sh", "-c", "java -version 2>&1")
        assert 'version "1.8.' in metadata["application_jvm"]
        metadata["images"] = {name: image_metadata(image) for name, image in
                              (("mysql", "mysql:8.4"), ("redis", "redis:6.2-alpine"), ("prometheus", "prom/prometheus:v3.5.0"))}
        metadata["versions"] = {"mysql": a.sql("SELECT VERSION();"), "redis": next(line.split(":", 1)[1].strip()
                                for line in a.redis("INFO", "server").splitlines() if line.startswith("redis_version:")),
                                "prometheus": a.compose("exec", "-T", "prometheus", "/bin/prometheus", "--version").splitlines()[0],
                                "compose": a.run(["docker", "compose", "version", "--short"])}
        rows = ["(%d,'synthetic-baseline-%04d',1,'/synthetic.png','synthetic','synthetic campus',120,30,%d,0,0,40,'08:00-20:00')" %
                (10000 + i, i, 20 + i % 50) for i in range(1, 1001)]
        a.sql("INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours) VALUES " + ",".join(rows) + ";")
        assert a.sql("SELECT COUNT(*) FROM tb_shop;") == "1001"
        body, _ = a.request(base, "/shop/search?keyword=synthetic-baseline&sort=price_asc&page=1&size=10")
        assert json.loads(body)["data"]["total"] == 1000
        for repeat in (1, 2, 3):
            for vus in (1, 10):
                for case in CASES:
                    if case == "shop_detail_warm":
                        a.redis("DEL", "cache:shop:v2:10001")
                        body, _ = a.request(base, "/shop/10001")
                        assert json.loads(body)["data"]["name"] == "synthetic-baseline-0001"
                    warm_path, warm, warm_times = trial(binary, target_port, case, vus, repeat, "5s")
                    before = fresh_snapshot(prom, warm_times["finished_at_epoch"])
                    path, measured, times = trial(binary, target_port, case, vus, repeat, "20s")
                    after = fresh_snapshot(prom, times["finished_at_epoch"])
                    delta = {key: after["cache"][key] - before["cache"][key] for key in CACHE}
                    assert all(value >= 0 for value in delta.values()), "unexpected process counter reset"
                    if case == "shop_detail_warm":
                        assert delta["first_hit"] == measured["requests"] and all(delta[key] == 0 for key in CACHE if key != "first_hit"), "warm-cache measurement window was not exclusively hits"
                    else:
                        assert all(value == 0 for value in delta.values()), "search unexpectedly touched cache"
                    stem = "%s-vu%d-r%d" % (case, vus, repeat)
                    files[stem + "-warmup.json"], files[stem + ".json"] = warm_path, path
                    runs.append({"case": case, "vus": vus, "repeat": repeat, "warmup_seconds": 5, "measurement_seconds": 20,
                                 "warmup": warm, "measured": measured, "wall_clock": times,
                                 "before": before, "after": after, "cache_delta": delta,
                                 "summary": stem + ".json", "warmup_summary": stem + "-warmup.json"})
                    print("PASS: %s VUs=%d repeat=%d measured requests=%d rate=%.2f/s p95=%.2fms p99=%.2fms" %
                          (case, vus, repeat, measured["requests"], measured["requests_per_second"],
                           measured["http_req_duration_ms"]["p(95)"], measured["http_req_duration_ms"]["p(99)"]), flush=True)
    finally:
        a.cleanup()
    report = {"schema_version": 1, "metadata": metadata, "dataset": {"total_shops": 1001, "matching_shops": 1000,
              "ids": "10001..11000", "keyword": "synthetic-baseline", "search_page": 1, "search_size": 10, "sort": "price_asc"},
              "model": {"executor": "constant-vus", "vus": [1, 10], "repeats": 3, "warmup_seconds": 5, "measurement_seconds": 20,
                        "graceful_stop_seconds": 5, "http_timeout_seconds": 5, "think_time": "none", "connection_reuse": True,
                        "network": "native macOS -> harness-owned localhost published port -> Docker VM app; no TLS/authentication",
                        "target_host": "127.0.0.1", "discovered_app_port": int(target_port)},
              "runs": runs, "aggregate": aggregate(runs), "cleanup_verified": True,
              "scope": "small same-host closed-loop read baseline, not peak capacity/production/SLO/optimization comparison",
              "raw_format": "unaltered k6 aggregate summary exports, not per-request samples",
              "files_sha256": {name: digest(path) for name, path in files.items()}}
    destination = a.ROOT / "docs/performance/results" / time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    publish(report, files, destination)
    print("Published validated baseline: " + str(destination.relative_to(a.ROOT)), flush=True)
    print("Private diagnostic artifacts: " + str(a.ARTIFACTS), flush=True)


if __name__ == "__main__":
    main()
