#!/usr/bin/env python3
"""Finite synthetic flash-sale cohorts, not steady-state capacity or production TPS."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import statistics
import time
import urllib.error
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location("read_baseline_support", Path(__file__).with_name("run-performance-baseline.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
a = b.a
a.PROJECT = "campushub-phase6e-test-" + uuid.uuid4().hex[:12]
a.COMPOSE[a.COMPOSE.index("-p") + 1] = a.PROJECT
SCRIPT = a.ROOT / "scripts/load/flash-sale.js"
COHORTS = (20, 200)
OUTCOMES = ("admission_new", "admission_replayed", "admission_sold_out", "unexpected_outcomes")


def integer(value, minimum=0):
    result = b.number(value)
    if result != int(result) or result < minimum:
        raise ValueError("expected bounded integer")
    return int(result)


def positive_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > 9223372036854775807:
        raise ValueError("expected lossless positive Java Long string")
    return value


def validate_fixture(fixture, actors):
    if actors not in (10, *COHORTS) or isinstance(actors, bool):
        raise ValueError("unsupported cohort")
    if set(fixture) != {"version", "activityId", "actors"} or type(fixture["version"]) is not int or fixture["version"] != 1:
        raise ValueError("unexpected fixture schema")
    positive_id(fixture["activityId"])
    values = fixture["actors"]
    if not isinstance(values, list) or len(values) != actors:
        raise ValueError("fixture size differs from configured VUs")
    identities, credentials = set(), set()
    for entry in values:
        if not isinstance(entry, dict) or set(entry) != {"id", "token"}:
            raise ValueError("unexpected actor schema")
        identity = positive_id(entry["id"])
        credential = entry["token"]
        if not isinstance(credential, str) or not re.fullmatch(r"[0-9a-f]{32}", credential):
            raise ValueError("invalid synthetic credential")
        identities.add(identity); credentials.add(credential)
    if len(identities) != actors or len(credentials) != actors:
        raise ValueError("duplicate synthetic identity or credential")
    return fixture


def validate_summary(summary, actors):
    if actors not in (10, *COHORTS) or isinstance(actors, bool):
        raise ValueError("unsupported cohort")
    values = lambda name: b.metric_values(summary, name)
    requests = integer(values("http_reqs")["count"], 1)
    iterations = integer(values("iterations")["count"], 1)
    if requests != 2 * actors or iterations != actors:
        raise ValueError("incomplete cohort or unexpected extra requests")
    checks = values("checks")
    if (b.number(checks.get("rate", checks.get("value"))) != 1
            or integer(checks["fails"]) != 0 or integer(checks["passes"]) != 6 * actors):
        raise ValueError("response/replay checks failed or missing")
    if b.number(values("http_req_failed").get("rate", values("http_req_failed").get("value"))) != 0:
        raise ValueError("unexpected transport/HTTP error")
    counts = {name: integer(values(name)["count"]) for name in OUTCOMES}
    expected = dict(admission_new=actors // 2, admission_replayed=actors // 2,
                    admission_sold_out=actors, unexpected_outcomes=0)
    if counts != expected:
        raise ValueError("outcome counts do not match the finite half-stock model")
    timing = {"all_http": b.timing_values(summary, "http_req_duration")}
    for name in ("blocked", "connecting", "tls_handshaking", "sending", "waiting", "receiving"):
        b.timing_values(summary, "http_req_" + name)
    for name in summary["metrics"]:
        if name.startswith("http_req_") and "{" in name and "min" in values(name):
            b.timing_values(summary, name)
    for outcome in OUTCOMES[:3]:
        timing[outcome] = b.timing_values(summary, outcome + "_ms")
    rate = b.number(values("http_reqs")["rate"])
    if rate <= 0:
        raise ValueError("invalid finite-batch request rate")
    return {"actors": actors, "initial_stock": actors // 2, "requests": requests,
            "iterations": iterations, "checks_passed": 6 * actors, "outcomes": counts,
            "batch_http_requests_per_second": rate, "client_duration_ms": timing,
            "unexpected_http_error_rate": 0}


def parse_rows(text, width):
    rows = [tuple(line.split("\t")) for line in text.splitlines()]
    if any(len(row) != width for row in rows):
        raise ValueError("malformed private database evidence")
    return rows


def pairs(value):
    if isinstance(value, dict):
        return list(value.items())
    if not isinstance(value, list) or len(value) % 2:
        raise ValueError("malformed Redis pair evidence")
    return list(zip(value[::2], value[1::2]))


def validate_witness(witness, actor_ids, accepted):
    if len(witness) != len(actor_ids):
        raise ValueError("missing or extra HTTP identity witnesses")
    identity_set = set(actor_ids)
    seen, admitted = set(), []
    for entry in witness:
        if not isinstance(entry, dict) or set(entry) != {"userId", "orderId", "outcome"}:
            raise ValueError("unexpected witness schema")
        identity = positive_id(entry["userId"])
        if identity not in identity_set or identity in seen:
            raise ValueError("foreign or duplicate HTTP identity")
        seen.add(identity)
        if entry["outcome"] == "accepted":
            admitted.append((identity, positive_id(entry["orderId"])))
        elif entry["outcome"] != "sold_out" or entry["orderId"] is not None:
            raise ValueError("unexpected HTTP outcome")
    if seen != identity_set or len(admitted) != len(accepted) or set(admitted) != set(accepted):
        raise ValueError("HTTP IDs differ from Redis accepted mapping")


def parse_witness_log(text):
    return [json.loads(line[len("COHORT_WITNESS "):]) for line in text.splitlines()
            if line.startswith("COHORT_WITNESS ")]


def validate_ledger(rows, accepted, participants, events, actor_ids, activity, stock, db_stock, redis_stock,
                    pending, delivered, last_stream_id, dead, attempts, failure_index, cancellations):
    positive_id(activity)
    identities = {positive_id(value) for value in actor_ids}
    if len(identities) != len(actor_ids) or len(identities) != 2 * stock or integer(stock, 1) not in (5, 10, 100):
        raise ValueError("invalid model population")
    mapping = {positive_id(user): positive_id(order) for user, order in accepted}
    if len(mapping) != len(accepted) or len(mapping) != stock or len(set(mapping.values())) != stock:
        raise ValueError("non-unique or incomplete accepted identities")
    if not set(mapping) <= identities or set(participants) != set(mapping) or len(participants) != stock:
        raise ValueError("participant identity mismatch")
    expected = {(order, user, activity, "1") for user, order in mapping.items()}
    if len(rows) != stock or set(rows) != expected:
        raise ValueError("durable unpaid orders differ from accepted mapping")
    expected_events = {(order, user, activity) for user, order in mapping.items()}
    if len(events) != stock or set(events) != expected_events:
        raise ValueError("Stream events are not exactly one per accepted identity")
    if integer(db_stock) != 0 or integer(redis_stock) != 0:
        raise ValueError("stock accounting mismatch")
    if integer(pending) != 0 or delivered != last_stream_id:
        raise ValueError("consumer has not drained the observed stream")
    if any(integer(value) != 0 for value in (dead, attempts, failure_index, cancellations)):
        raise ValueError("failure/archive/retry/outbox evidence is not clean")
    return verified_ledger(stock)


def verified_ledger(stock):
    return {"new_orders": stock, "unique_users": stock, "unique_order_ids": stock,
            "stream_events": stock, "database_stock": 0, "redis_stock": 0,
            "pending": 0, "dead_entries": 0, "invariants_verified": True}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def owned_request(base, route, expected=200, method="GET", body=None, headers=None):
    if not re.fullmatch(r"http://127\.0\.0\.1:[1-9][0-9]{0,4}", base) or int(base.rsplit(":", 1)[1]) > 65535:
        raise ValueError("requests require discovered localhost target")
    if not route.startswith("/") or route.startswith("//"):
        raise ValueError("invalid relative owned route")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(base + route, data=body, headers=headers or {}, method=method)
    try:
        response = opener.open(request, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        if response.status != expected:
            raise ValueError("unexpected owned-target HTTP status")
        return response.read(), dict(response.headers)


def http(base, route, credential, payload):
    body, _ = owned_request(base, route, method="POST", body=json.dumps(payload).encode(),
                        headers={"authorization": credential, "Content-Type": "application/json"})
    result = json.loads(body)
    if result.get("success") is not True:
        raise ValueError("fixture creation failed")
    return result


def seed_actors(base):
    count = max(COHORTS)
    rows = ["(%d,'%011d','synthetic-load-%d')" % (900001 + i, i + 1, i + 1) for i in range(count)]
    rows.append("(900999,'00000900999','synthetic-load-admin')")
    a.sql("INSERT INTO tb_user(id,phone,nick_name) VALUES " + ",".join(rows) + ";"
          "INSERT INTO tb_user_role(user_id,role) SELECT id,'USER' FROM tb_user WHERE id BETWEEN 900001 AND 900200;"
          "INSERT INTO tb_user_role(user_id,role) VALUES(900999,'ADMIN');")
    actors = [{"id": str(900001 + i), "token": uuid.uuid4().hex} for i in range(count)]
    admin = uuid.uuid4().hex
    # Pipeline only these owned session records; no subprocess arguments contain their values.
    commands = []
    for entry in [*actors, {"id": "900999", "token": admin}]:
        key = "login:token:" + entry["token"]
        commands.extend(["HSET %s id %s nickName synthetic-load" % (key, entry["id"]), "EXPIRE %s 1800" % key])
    a.compose("exec", "-T", "redis", "sh", "-ec", 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli',
              data=("\n".join(commands) + "\n").encode())
    # Exercise actual auth before measuring; this is synthetic session provisioning, not login/SMS load.
    for entry in (actors[0], actors[-1]):
        body, _ = owned_request(base, "/user/me", headers={"authorization": entry["token"]})
        if str(json.loads(body)["data"]["id"]) != entry["id"]:
            raise ValueError("fixture session identity not authenticated")
    return actors, admin


def create_activity(base, admin, stock):
    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    result = http(base, "/voucher/seckill", admin, {"shopId": 1, "title": "synthetic finite load",
        "payValue": 100, "actualValue": 200, "stock": stock,
        "beginTime": (now - datetime.timedelta(minutes=1)).isoformat(timespec="seconds"),
        "endTime": (now + datetime.timedelta(minutes=30)).isoformat(timespec="seconds")})
    # Activity IDs are generated by the DB and returned as JSON numbers; handle only exact positive integers.
    activity = str(integer(result["data"], 1))
    positive_id(activity)
    if a.redis("GET", "seckill:stock:" + activity) != str(stock):
        raise ValueError("activity Redis publication not confirmed")
    return activity


def private_fixture(folder, fixture, actors):
    validate_fixture(fixture, actors)
    folder.mkdir(mode=0o700)
    path = folder / "campushub-flash-sale-fixture.json"
    with path.open("x") as output:
        os.chmod(path, 0o600)
        json.dump(fixture, output)
    return path


def native_args(binary, folder, fixture, actors, target_port, inspect=False):
    if not isinstance(target_port, str) or not re.fullmatch(r"[1-9][0-9]{0,4}", target_port) or int(target_port) > 65535:
        raise ValueError("invalid discovered localhost port")
    if fixture.parent.resolve() != folder.resolve() or fixture.name != "campushub-flash-sale-fixture.json":
        raise ValueError("fixture must belong to this private cohort folder")
    validate_fixture(json.loads(fixture.read_text()), actors)
    config = folder / "empty-config.json"
    if not config.exists():
        with config.open("x") as output:
            os.chmod(config, 0o600); output.write("{}")
    action = ["inspect", "--execution-requirements"] if inspect else ["run", "--no-usage-report", "--summary-export", str(folder / "summary.json")]
    return [str(binary), "--config", str(config), "--address", "127.0.0.1:0", "--no-color", "--log-format", "raw", *action,
            "--include-system-env-vars=false", "--env", "TARGET_PORT=" + target_port,
            "--env", "ACTORS=" + str(actors), "--env", "FIXTURE_PATH=" + str(fixture), str(SCRIPT)]


def cohort(binary, base, actors, admin, repeat, warm=False):
    if b.digest(binary) != b.K6_SHA256:
        raise ValueError("native executable changed before cohort")
    population = len(actors); stock = population // 2
    activity = create_activity(base, admin, stock)
    stream_before = integer(a.redis("XLEN", "stream.orders"))
    prefix = "warmup" if warm else "measured"
    folder = a.ARTIFACTS / ("%s-n%d-r%d" % (prefix, population, repeat))
    fixture = private_fixture(folder, {"version": 1, "activityId": activity, "actors": actors}, population)
    # Clock is driver monotonic. The drain interval includes k6 exit/export and observation overhead;
    # it is not a per-order admission-to-commit latency or an exact completion timestamp.
    start = time.monotonic()
    private_log = b.native_run(native_args(binary, folder, fixture, population, base.rsplit(":", 1)[1]), timeout=50)
    load_end = time.monotonic()
    raw = folder / "summary.json"; raw.chmod(0o600)
    measured = validate_summary(json.loads(raw.read_text()), population)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        count = integer(a.sql("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%s;" % activity))
        pending = integer(a.redis("XPENDING", "stream.orders", "g1").splitlines()[0])
        if count == stock and pending == 0:
            break
        time.sleep(.5)
    else:
        raise ValueError("finite cohort did not drain within observation budget")
    observed_end = time.monotonic()
    accepted = pairs(json.loads(a.redis("--json", "HGETALL", "seckill:request:" + activity)))
    validate_witness(parse_witness_log(private_log), [entry["id"] for entry in actors], accepted)
    participants = json.loads(a.redis("--json", "SMEMBERS", "seckill:order:" + activity))
    rows = parse_rows(a.sql("SELECT id,user_id,voucher_id,status FROM tb_voucher_order WHERE voucher_id=%s ORDER BY id;" % activity), 4)
    stream = json.loads(a.redis("--json", "XRANGE", "stream.orders", "-", "+"))
    events = []
    for source_id, fields in stream:
        parsed = pairs(fields)
        values = dict(parsed)
        if len(parsed) != len(values):
            raise ValueError("duplicate Stream event fields")
        if values.get("voucherId") == activity:
            if set(values) != {"id", "userId", "voucherId"}:
                raise ValueError("unexpected event fields")
            events.append((values["id"], values["userId"], values["voucherId"]))
    stream_length = integer(a.redis("XLEN", "stream.orders"))
    if stream_length - stream_before != stock:
        raise ValueError("replay appended duplicate events")
    groups = json.loads(a.redis("--json", "XINFO", "GROUPS", "stream.orders"))
    group = [dict(pairs(item)) for item in groups]
    if len(group) != 1 or group[0]["name"] != "g1":
        raise ValueError("unexpected consumer group")
    # last-delivered-id must equal the current last Stream ID, including acknowledged records.
    ledger = validate_ledger(rows, accepted, participants, events, [entry["id"] for entry in actors], activity,
        stock, integer(a.sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%s;" % activity)),
        integer(a.redis("GET", "seckill:stock:" + activity)), integer(group[0]["pending"]),
        group[0]["last-delivered-id"], stream[-1][0], integer(a.redis("XLEN", "stream.orders.dead")),
        integer(a.redis("HLEN", "stream.orders.attempts")), integer(a.redis("HLEN", "stream.orders.failures")),
        integer(a.sql("SELECT COUNT(*) FROM tb_order_cancellation;")))
    return raw, {"repeat": repeat, "measured": measured, "ledger": ledger,
        "driver_load_wall_seconds": load_end - start,
        "load_exit_to_all_orders_observed_seconds": observed_end - load_end,
        "observation_poll_seconds": .5, "observation_timeout_seconds": 90}


def aggregate(runs):
    if len(runs) != 6:
        raise ValueError("six formal cohorts required")
    output = []
    for actors in COHORTS:
        selected = [row for row in runs if row["measured"]["actors"] == actors]
        if sorted(row["repeat"] for row in selected) != [1, 2, 3]:
            raise ValueError("three distinct repeats required per population")
        values = {"load_exit_to_all_orders_observed_seconds": [row["load_exit_to_all_orders_observed_seconds"] for row in selected]}
        for outcome in OUTCOMES[:3]:
            for quantile in ("p(95)", "p(99)"):
                values[outcome + "_per_run_" + quantile + "_ms"] = [row["measured"]["client_duration_ms"][outcome][quantile] for row in selected]
        output.append({"actors": actors, "repeats": 3, **{key: {"median": statistics.median([b.number(v) for v in sample]),
            "min": min(sample), "max": max(sample)} for key, sample in values.items()}})
    return output


def publish(report, files, destination):
    if report.get("cleanup_verified") is not True or report.get("scope") != "finite synthetic flash-sale cohorts; not capacity/TPS/SLO/per-order commit latency":
        raise ValueError("publication cleanup/scope gate failed")
    if report.get("aggregate") != aggregate(report["runs"]):
        raise ValueError("aggregate differs from validated cohorts")
    expected = {"flash-sale-n%d-r%d%s.json" % (n, r, suffix) for n in COHORTS for r in (1, 2, 3) for suffix in ("", "-warmup")}
    if set(files) != expected or set(report["files_sha256"]) != expected:
        raise ValueError("incomplete or unexpected raw exports")
    content = json.dumps(report, indent=2, allow_nan=False).encode()
    raw = {name: path.read_bytes() for name, path in files.items()}
    for name, data in raw.items():
        if hashlib.sha256(data).hexdigest() != report["files_sha256"][name]:
            raise ValueError("raw export changed before publication")
        match = re.fullmatch(r"flash-sale-n(20|200)-r([123])(-warmup)?\.json", name)
        population = 10 if match[3] else int(match[1])
        measured = validate_summary(json.loads(data), population)
        row = next(row for row in report["runs"] if row["measured"]["actors"] == int(match[1]) and row["repeat"] == int(match[2]))
        evidence = row["warmup"] if match[3] else row
        if measured != evidence["measured"] or evidence["ledger"] != verified_ledger(population // 2):
            raise ValueError("formal/warmup evidence does not match report")
        for field in ("driver_load_wall_seconds", "load_exit_to_all_orders_observed_seconds"):
            b.number(evidence[field])
        if re.search(rb"/Users/|/private/tmp/|/var/folders/|(?i:authorization|password|token|phone|session|cookie)|[0-9a-f]{32}(?![0-9a-f])", data):
            raise ValueError("private field in raw public export")
    # SHA hashes are intentionally public in the manifest; private credentials never are.
    if re.search(rb"/Users/|/private/tmp/|/var/folders/|(?i:authorization|password|token|phone|session|cookie)", content):
        raise ValueError("private field in public report")
    destination.mkdir(parents=True, exist_ok=False)
    for name, data in raw.items():
        (destination / name).write_bytes(data)
    (destination / "manifest.json").write_bytes(content)


def validate_inspect(result, population):
    # Pinned k6 1.3.0 inspect exports options directly, not under an "options" wrapper.
    scenarios = result["scenarios"]
    if set(scenarios) != {"flash_sale"}:
        raise ValueError("unexpected native scenario")
    scenario = scenarios["flash_sale"]
    if (scenario["executor"] != "per-vu-iterations" or scenario["vus"] != population
            or scenario["iterations"] != 1 or scenario["maxDuration"] != "30s"
            or scenario["gracefulStop"] != "5s" or result["maxRedirects"] != 0 or result["systemTags"] not in (None, [])):
        raise ValueError("native options differ from finite-cohort contract")


def inspect_only(binary):
    valid = None
    for population in (10, *COHORTS):
        actors = [{"id": str(i + 1), "token": "%032x" % (i + 1)} for i in range(population)]
        folder = a.ARTIFACTS / ("inspect-n%d" % population)
        fixture = private_fixture(folder, {"version": 1, "activityId": "1", "actors": actors}, population)
        args = native_args(binary, folder, fixture, population, "1", inspect=True)
        validate_inspect(json.loads(b.native_run(args)), population)
        if population == 10:
            valid = args
    rejected = 0
    for key, value in (("TARGET_PORT", "0"), ("TARGET_PORT", "01"), ("TARGET_PORT", "65536"),
        ("TARGET_PORT", "12a"), ("TARGET_PORT", "http://example.com"), ("BASE_URL", ""),
        ("ACTORS", "1"), ("ACTORS", "201"), ("FIXTURE_PATH", "relative/campushub-flash-sale-fixture.json")):
        invalid = list(valid)
        positions = [index for index, argument in enumerate(invalid) if argument.startswith(key + "=")]
        if positions:
            invalid[positions[0]] = key + "=" + value
        else:
            invalid[-1:-1] = ["--env", key + "=" + value]
        try:
            b.native_run(invalid)
        except RuntimeError:
            rejected += 1
        else:
            raise ValueError("native inspect accepted forbidden input")
    print("PASS: native inspect accepted 10/20/200-actor cohorts and rejected %d invalid inputs; no HTTP requests" % rejected, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--inspect-only", action="store_true", help="native parse/options checks; no Docker or HTTP")
    modes.add_argument("--run", action="store_true", help="explicitly create test-owned Compose resources and measure six cohorts")
    args = parser.parse_args()
    binary = b.native_binary()
    if args.inspect_only:
        inspect_only(binary); return
    if a.ENV.get("DOCKER_HOST") and not a.ENV["DOCKER_HOST"].startswith("unix://"):
        raise RuntimeError("only local Docker allowed")
    if not a.run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"] ).startswith("unix://"):
        raise RuntimeError("local Unix socket Docker required")
    jar = a.ROOT / "target/campushub-0.0.1-SNAPSHOT.jar"
    if not jar.is_file():
        raise RuntimeError("build the Java 8 package first")
    a.run(["git", "diff", "--quiet", "HEAD", "--", "src/main", "pom.xml", "deploy", "Dockerfile", "compose.yaml", "compose.monitoring.yaml", "compose.performance.yaml"])
    if a.compose("ps", "-aq") or a.run(["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + a.PROJECT, "--format", "{{.Name}}"]):
        raise RuntimeError("fresh owned project required")
    metadata = {"application_revision": a.run(["git", "rev-parse", "HEAD"]), "application_jar_sha256": b.digest(jar),
        "k6_version": b.native_run([str(binary), "version"]), "k6_sha256": b.digest(binary),
        "harness_sha256": b.digest(Path(__file__)), "script_sha256": b.digest(SCRIPT),
        "support_sha256": {name: b.digest(a.ROOT / "scripts" / name) for name in (
            "run-performance-baseline.py", "verify-compose.py", "verify-observability.py", "prepare-native-k6.py")},
        "overlay_sha256": b.digest(a.ROOT / "compose.performance.yaml"),
        "host": {"os": platform.system(), "version": platform.mac_ver()[0], "architecture": platform.machine(),
            "model": a.run(["sysctl", "-n", "hw.model"]), "cpu": a.run(["sysctl", "-n", "machdep.cpu.brand_string"]),
            "cpus": integer(int(a.run(["sysctl", "-n", "hw.ncpu"]))), "memory_bytes": int(a.run(["sysctl", "-n", "hw.memsize"]))}}
    files, runs = {}, []
    try:
        a.compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=240)
        base = a.app_base()
        actors, admin = seed_actors(base)
        metadata["resources"] = {}
        for service, cpus, mib in (("app", 2, 768), ("mysql", 1, 768), ("redis", .5, 256), ("prometheus", .5, 256)):
            container = a.compose("ps", "-q", service)
            actual = json.loads(a.run(["docker", "inspect", container, "--format", '{"nano_cpus":{{.HostConfig.NanoCpus}},"memory_bytes":{{.HostConfig.Memory}}}']))
            if actual != {"nano_cpus": int(cpus * 1e9), "memory_bytes": mib * 1024 ** 2}:
                raise ValueError("container resource limits differ from contract")
            metadata["resources"][service] = actual
        metadata["app_jvm"] = a.compose("exec", "-T", "app", "sh", "-c", "java -version 2>&1")
        if 'version "1.8.' not in metadata["app_jvm"]:
            raise ValueError("app JDK not 8")
        metadata["versions"] = {"mysql": a.sql("SELECT VERSION();"), "redis": next(
            line.split(":", 1)[1].strip() for line in a.redis("INFO", "server").splitlines() if line.startswith("redis_version:")),
            "prometheus": a.compose("exec", "-T", "prometheus", "/bin/prometheus", "--version").splitlines()[0],
            "compose": a.run(["docker", "compose", "version", "--short"])}
        metadata["docker"] = json.loads(a.run(["docker", "info", "--format", '{"version":{{json .ServerVersion}},"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}}}']))
        for repeat in (1, 2, 3):
            for population in COHORTS:
                warm_raw, warm = cohort(binary, base, actors[:10], admin, repeat * 1000 + population, warm=True)
                raw, row = cohort(binary, base, actors[:population], admin, repeat)
                stem = "flash-sale-n%d-r%d" % (population, repeat)
                files[stem + "-warmup.json"], files[stem + ".json"] = warm_raw, raw
                row["summary"] = stem + ".json"; row["warmup_summary"] = stem + "-warmup.json"
                row["warmup"] = warm
                runs.append(row)
                print("PASS: finite cohort actors=%d repeat=%d requests=%d new-orders=%d invariants verified" % (
                    population, repeat, row["measured"]["requests"], row["ledger"]["new_orders"]), flush=True)
    finally:
        a.cleanup()
    report = {"schema_version": 1, "scope": "finite synthetic flash-sale cohorts; not capacity/TPS/SLO/per-order commit latency",
        "metadata": metadata, "model": {"executor": "per-vu-iterations", "actors": list(COHORTS), "iterations_per_actor": 1,
            "requests_per_actor": 2, "stock_fraction": .5, "repeats": 3, "warmup_actors": 10, "max_duration_seconds": 30,
            "graceful_stop_seconds": 5, "http_timeout_seconds": 5, "runner_gomaxprocs": 1, "runner_gomemlimit": "256MiB",
            "runner_limits": "Go settings only, no OS hard quota", "identity_provisioning": "synthetic fixture only, not login/SMS load"},
        "runs": runs, "aggregate": aggregate(runs), "cleanup_verified": True,
        "raw_format": "unaltered k6 aggregate summary exports, not per-request samples",
        "files_sha256": {name: b.digest(path) for name, path in files.items()}}
    destination = a.ROOT / "docs/performance/flash-sale-results" / time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    publish(report, files, destination)
    print("Published finite-cohort evidence: " + str(destination.relative_to(a.ROOT)), flush=True)
    print("Private diagnostics remain in: " + str(a.ARTIFACTS), flush=True)


if __name__ == "__main__":
    main()
