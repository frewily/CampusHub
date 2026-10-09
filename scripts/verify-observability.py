#!/usr/bin/env python3
"""Verify real Prometheus ingestion in a fresh, synthetic local Compose project."""
import json
import importlib.util
import os
from pathlib import Path
import re
import time
import urllib.parse
import uuid

spec = importlib.util.spec_from_file_location("compose_acceptance", Path(__file__).with_name("verify-compose.py"))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)

# Reuse the existing safe command/log/cleanup primitives, never its business main().
acceptance.PROJECT = "campushub-phase6a-test-" + uuid.uuid4().hex[:12]
acceptance.COMPOSE = ["docker", "compose", "--env-file", str(acceptance.ROOT / ".env.example"),
                      "-f", str(acceptance.ROOT / "compose.yaml"),
                      "-f", str(acceptance.ROOT / "compose.monitoring.yaml"), "-p", acceptance.PROJECT,
                      "--profile", "app"]
acceptance.ENV["PROMETHEUS_PUBLISHED_PORT"] = "0"


def query(base, expression):
    body, _ = acceptance.request(base, "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
    data = json.loads(body)
    assert data["status"] == "success", "Prometheus query failed"
    return data["data"]["result"]


def await_series(base, expression):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            result = query(base, expression)
            if result:
                return result
        except (OSError, AssertionError):
            pass
        time.sleep(1)
    raise RuntimeError("Prometheus did not ingest expected series")


def main():
    if acceptance.ENV.get("DOCKER_HOST") and not acceptance.ENV["DOCKER_HOST"].startswith("unix://"):
        raise RuntimeError("acceptance rejects non-local DOCKER_HOST overrides")
    endpoint = acceptance.run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])
    assert endpoint.startswith("unix://"), "acceptance requires a local Docker engine"
    assert acceptance.compose("--profile", "app", "ps", "-aq") == "", "project must be fresh"
    assert acceptance.run(["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + acceptance.PROJECT,
                           "--format", "{{.Name}}"] ) == "", "project volumes must be fresh"
    try:
        acceptance.compose("--profile", "app", "up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=240)
        acceptance.compose("exec", "-T", "prometheus", "/bin/promtool", "check", "config", "/etc/prometheus/prometheus.yml")
        app = acceptance.app_base()
        port = acceptance.compose("port", "app", "9090")
        assert re.fullmatch(r"127\.0\.0\.1:[0-9]+", port)
        prom = "http://" + port
        config = json.loads(acceptance.compose("--profile", "app", "config", "--format", "json"))
        assert 8082 not in [int(p["target"]) for p in config["services"]["app"]["ports"]]
        acceptance.run([str(acceptance.ROOT / "scripts/smoke-test.sh"), app])
        await_series(prom, 'up{job="campushub"} == 1')
        acceptance.check("real Prometheus scrape is UP; management port is not published")

        for route in ("/actuator/prometheus", "/actuator/env", "/actuator/heapdump"):
            acceptance.request(app, route, 401)
        acceptance.check("business listener does not expose anonymous Actuator endpoints")

        marker = "synthetic-phase6a-query-marker"
        body, headers = acceptance.request(app, "/shop/search?keyword=" + marker,
                                           headers={"X-Request-Id": "untrusted-client-id"})
        assert json.loads(body)["success"] is True
        uuid.UUID(headers["X-Request-Id"])
        assert headers["X-Request-Id"] != "untrusted-client-id"
        acceptance.request(app, "/shop/search?page=0", 400)
        _, denied_headers = acceptance.request(app, "/upload/blog/delete?name=synthetic", 401)
        uuid.UUID(denied_headers["X-Request-Id"])
        await_series(prom, 'http_server_requests_seconds_count{uri="/shop/search",status="400"}')
        await_series(prom, 'http_server_requests_seconds_bucket{uri="/shop/search",status="200"}')
        await_series(prom, 'http_server_requests_seconds_count{status="401"}')
        acceptance.check("generated request IDs and HTTP success/error histogram series are collected")

        await_series(prom, 'jvm_memory_used_bytes{application="campushub"}')
        await_series(prom, 'hikaricp_connections{application="campushub"}')
        samples = json.dumps(query(prom, '{application="campushub"}'))
        assert marker not in samples and "untrusted-client-id" not in samples and headers["X-Request-Id"] not in samples
        assert acceptance.ENV["DB_PASSWORD"] not in samples and acceptance.ENV["REDIS_PASSWORD"] not in samples
        logs = acceptance.compose("logs", "--no-color", "app")
        summary = "\n".join(line for line in logs.splitlines() if "request method=" in line)
        assert summary and marker not in summary and "untrusted-client-id" not in summary
        acceptance.check("JVM and Hikari metrics exist; samples and request summaries exclude fixture secrets/identifiers")

        acceptance.compose("stop", "redis")
        outage_started = time.time()
        body, _ = acceptance.request(app, "/health/ready", 503)
        assert json.loads(body) == {"status": "DOWN"}
        acceptance.request(app, "/health/live")
        # Observe a new actual scrape timestamp, not just the pre-outage UP sample.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            timestamp = query(prom, 'timestamp(up{job="campushub"})')
            if timestamp and float(timestamp[0]["value"][1]) > outage_started:
                assert query(prom, 'up{job="campushub"} == 1')
                break
            time.sleep(1)
        else:
            raise RuntimeError("no fresh scrape during dependency outage")
        acceptance.check("dependency outage is DOWN while a fresh Prometheus scrape still succeeds")
        acceptance.compose("up", "-d", "--wait", "--wait-timeout", "60", "redis")
        acceptance.wait_ready(app)
        version = acceptance.compose("exec", "-T", "prometheus", "/bin/prometheus", "--version").splitlines()[0]
        report = {"checks": acceptance.PASSED, "prometheus": version,
                  "scope": "synthetic correctness/scrape acceptance; not load, latency or production reliability evidence"}
        path = acceptance.ARTIFACTS / "observability-result.json"
        path.write_text(json.dumps(report, indent=2)); os.chmod(path, 0o600)
    finally:
        acceptance.cleanup()
    print("Private acceptance artifacts: " + str(acceptance.ARTIFACTS), flush=True)


if __name__ == "__main__":
    main()
