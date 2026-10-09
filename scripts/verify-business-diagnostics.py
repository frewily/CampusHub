#!/usr/bin/env python3
"""Synthetic target-version HTTP/fault acceptance, not a load test or a production monitor."""
import datetime
import importlib.util
import json
import os
from pathlib import Path
import re
import time
import uuid

spec = importlib.util.spec_from_file_location("observability_acceptance", Path(__file__).with_name("verify-observability.py"))
monitoring = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitoring)
a = monitoring.acceptance
a.PROJECT = "campushub-phase6b-test-" + uuid.uuid4().hex[:12]
a.COMPOSE[a.COMPOSE.index("-p") + 1] = a.PROJECT
a.ENV.update(ORDER_CLAIM_IDLE_MS="1000", ORDER_MAX_ATTEMPTS="20")


def http(base, route, token=None, expected=200, method="GET", payload=None):
    headers = {"authorization": token} if token else {}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    body, _ = a.request(base, route, expected, method, data, headers)
    result = json.loads(body)
    if expected == 200:
        assert result["success"] is True, "business request was not successful"
    return result


def eventually(check, description, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.5)
    raise AssertionError("timed out: " + description)


def metric(prom, family, outcome):
    expression = '%s{outcome="%s",job="campushub"}' % (family, outcome)
    samples = monitoring.query(prom, expression)
    assert len(samples) <= 1, "unexpected duplicate target series"
    # Missing/stale exporter evidence must not masquerade as a zero counter.
    return float(samples[0]["value"][1]) if samples else float("nan")


def await_metric(prom, family, outcome, minimum=1):
    eventually(lambda: metric(prom, family, outcome) >= minimum, family + "/" + outcome)


def login(base, phone):
    http(base, "/user/code?phone=" + phone, method="POST")
    http(base, "/user/code?phone=" + phone, expected=429, method="POST")
    # ONLY this unique synthetic Redis contains these codes. No real SMS or credentials are used.
    code = a.redis("GET", "login:code:" + phone)
    assert re.fullmatch(r"[0-9]{6}", code)
    wrong = "000000" if code != "000000" else "000001"
    http(base, "/user/login", expected=401, method="POST", payload={"phone": phone, "code": wrong})
    token = http(base, "/user/login", method="POST", payload={"phone": phone, "code": code})["data"]
    http(base, "/user/login", expected=401, method="POST", payload={"phone": phone, "code": code})
    user = http(base, "/user/me", token)["data"]["id"]
    return token, int(user), code


def main():
    if a.ENV.get("DOCKER_HOST") and not a.ENV["DOCKER_HOST"].startswith("unix://"):
        raise RuntimeError("acceptance rejects non-local Docker overrides")
    endpoint = a.run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])
    assert endpoint.startswith("unix://"), "acceptance requires a local engine"
    assert (a.ROOT / "target/campushub-0.0.1-SNAPSHOT.jar").is_file(), "build Java 8 package first"
    assert a.compose("ps", "-aq") == "", "project must be fresh"
    assert a.run(["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + a.PROJECT,
                  "--format", "{{.Name}}"] ) == "", "volumes must be fresh"
    try:
        a.compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=240)
        base = a.app_base()
        port = a.compose("port", "app", "9090")
        assert re.fullmatch(r"127\.0\.0\.1:[0-9]+", port)
        prom = "http://" + port
        monitoring.await_series(prom, 'up{job="campushub"} == 1')
        alice, alice_id, alice_code = login(base, "13900000001")
        bob, bob_id, bob_code = login(base, "13900000002")
        a.sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'ADMIN');" % alice_id)
        http(base, "/shop", bob, expected=403, method="PUT", payload={"id": 1, "name": "denied"})
        http(base, "/shop", expected=401, method="PUT", payload={"id": 1, "name": "denied"})
        assert a.sql("SELECT name FROM tb_shop WHERE id=1;") == "校园示例食堂"
        a.check("real code consumption, cooldown, login, USER denial and ADMIN fixture through actual security")

        http(base, "/follow/%d/true" % alice_id, bob, method="PUT")
        http(base, "/follow/%d/true" % alice_id, bob, method="PUT")
        assert a.sql("SELECT COUNT(*) FROM tb_follow WHERE user_id=%d AND follow_user_id=%d;" % (bob_id, alice_id)) == "1"
        post = http(base, "/blog", alice, method="POST", payload={"shopId": 1, "title": "synthetic diagnostic post",
                    "images": "/synthetic.png", "content": "synthetic only", "userId": bob_id})["data"]
        feed = http(base, "/blog/of/follow?lastId=%d" % int(time.time() * 1000 + 1000), bob)["data"]
        assert any(str(item["id"]) == str(post) for item in feed["list"])
        assert a.redis("ZCARD", "feed:" + str(alice_id)) == "0"
        assert a.sql("SELECT user_id FROM tb_blog WHERE id=%d;" % int(post)) == str(alice_id)
        a.check("real follow uniqueness and Feed delivery to follower, with server-owned author identity")

        for _ in range(2):
            http(base, "/shop/1")
            http(base, "/shop/999999", expected=404)
        await_metric(prom, "campushub_shop_cache_events_total", "first_hit")
        await_metric(prom, "campushub_shop_cache_events_total", "first_negative_hit")
        await_metric(prom, "campushub_shop_cache_events_total", "first_miss", 2)
        # Wrong-type epoch fails Redis invalidation, while the authorized DB mutation commits.
        a.redis("DEL", "cache:shop:epoch:1")
        a.redis("HSET", "cache:shop:epoch:1", "synthetic", "wrong-type")
        http(base, "/shop", alice, method="PUT", payload={"id": 1, "name": "synthetic updated shop"})
        assert a.sql("SELECT name FROM tb_shop WHERE id=1;") == "synthetic updated shop"
        eventually(lambda: int(a.sql("SELECT attempts FROM tb_shop_cache_invalidation WHERE shop_id=1;") or "0") >= 1,
                   "cache invalidation retry persisted")
        await_metric(prom, "campushub_shop_invalidation_events_total", "callback_failed")
        await_metric(prom, "campushub_shop_invalidation_events_total", "retry_recorded")
        assert metric(prom, "campushub_shop_invalidation_events_total", "completed") == 0
        a.redis("DEL", "cache:shop:epoch:1")
        eventually(lambda: a.sql("SELECT COUNT(*) FROM tb_shop_cache_invalidation WHERE shop_id=1;") == "0", "cache outbox recovered")
        assert http(base, "/shop/1")["data"]["name"] == "synthetic updated shop"
        await_metric(prom, "campushub_shop_invalidation_events_total", "completed")
        a.check("cache hit/miss/negative series and committed outbox failure/recovery, not premature completion")

        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        activity = http(base, "/voucher/seckill", alice, method="POST", payload={"shopId": 1,
                    "title": "synthetic diagnostic activity", "payValue": 100, "actualValue": 200, "stock": 2,
                    "beginTime": (now - datetime.timedelta(minutes=1)).isoformat(timespec="seconds"),
                    "endTime": (now + datetime.timedelta(minutes=10)).isoformat(timespec="seconds")})["data"]
        activity = int(activity)
        route = "/voucher-order/seckill/%d" % activity
        # Existing policy deliberately prevents ADMIN participation, even with a USER role.
        http(base, route, alice, expected=403, method="POST")
        assert a.redis("GET", "seckill:stock:" + str(activity)) == "2"
        a.sql("DELETE FROM tb_user_role WHERE user_id=%d AND role='ADMIN';" % alice_id)
        order = http(base, route, alice, method="POST")["orderId"]
        replay = http(base, route, alice, method="POST")
        assert replay["orderId"] == order and replay["replayed"] is True
        order_route = "/voucher-order/%s?voucherId=%d" % (order, activity)
        eventually(lambda: http(base, order_route, alice)["data"]["status"] == "PENDING_PAYMENT", "order persisted")
        http(base, order_route, bob, expected=404)
        await_metric(prom, "campushub_orders_consumer_events_total", "handler_returned")
        await_metric(prom, "campushub_orders_consumer_events_total", "acknowledged")
        assert a.sql("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=%d AND voucher_id=%d;" % (alice_id, activity)) == "1"
        a.check("actual activity creation, admission replay, durable order, owner isolation and confirmed ACK series")

        # Fail only DB completion AFTER Lua releases. Expiry is later accelerated solely in this synthetic lease.
        # mysql CLI splits at ';', unlike the JDBC fixture: preserve the compound trigger as one statement.
        a.sql("DELIMITER //\nCREATE TRIGGER synthetic_completion_fault BEFORE UPDATE ON tb_order_cancellation FOR EACH ROW "
              "BEGIN IF NEW.status='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic completion fault'; END IF; END//\nDELIMITER ;\n")
        http(base, "/voucher-order/%s/cancel?voucherId=%d" % (order, activity), alice, method="POST")
        eventually(lambda: a.redis("HGET", "seckill:released:" + str(activity), order) == "DONE:" + str(alice_id), "Redis release done")
        await_metric(prom, "campushub_orders_cancellation_events_total", "entry_failure")
        assert a.sql("SELECT status FROM tb_order_cancellation WHERE order_id=%s;" % order) == "PENDING"
        assert a.redis("GET", "seckill:stock:" + str(activity)) == "2"
        assert metric(prom, "campushub_orders_cancellation_events_total", "released") == 0
        a.sql("DROP TRIGGER synthetic_completion_fault; UPDATE tb_order_cancellation "
              "SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE order_id=%s;" % order)
        eventually(lambda: http(base, order_route, alice)["data"]["cancellationCompensation"] == "COMPLETED", "cancellation confirmed")
        await_metric(prom, "campushub_orders_cancellation_events_total", "already_released")
        assert a.redis("GET", "seckill:stock:" + str(activity)) == "2"
        assert a.sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d;" % activity) == "2"
        a.check("Redis release plus failed DB completion remains pending; replay confirms without double stock return")

        a.sql("CREATE TRIGGER synthetic_order_fault BEFORE INSERT ON tb_voucher_order FOR EACH ROW "
              "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic persistence fault';")
        second = http(base, route, bob, method="POST")["orderId"]
        assert http(base, route, bob, method="POST")["orderId"] == second
        await_metric(prom, "campushub_orders_consumer_events_total", "deferred")
        assert a.sql("SELECT COUNT(*) FROM tb_voucher_order WHERE id=%s;" % second) == "0"
        assert int(a.redis("XPENDING", "stream.orders", "g1").splitlines()[0]) >= 1
        # Graceful app stop/restart, not a process kill or Redis failover test.
        # Restart the namespace-sharing collector alongside the application listener.
        a.compose("stop", "prometheus", "app")
        a.sql("DROP TRIGGER synthetic_order_fault;")
        restart_started = time.time()
        a.compose("up", "-d", "--wait", "--wait-timeout", "90", "app", "prometheus", timeout=120)
        base = a.app_base()
        port = a.compose("port", "app", "9090")
        assert re.fullmatch(r"127\.0\.0\.1:[0-9]+", port)
        prom = "http://" + port
        a.wait_ready(base)
        def fresh_scrape():
            timestamps = monitoring.query(prom, 'timestamp(up{job="campushub"})')
            return (bool(timestamps) and float(timestamps[0]["value"][1]) > restart_started
                    and bool(monitoring.query(prom, 'up{job="campushub"} == 1')))
        eventually(fresh_scrape, "fresh successful scrape after application/collector restart")
        second_route = "/voucher-order/%s?voucherId=%d" % (second, activity)
        eventually(lambda: http(base, second_route, bob)["data"]["status"] == "PENDING_PAYMENT", "pending recovered after restart")
        assert a.sql("SELECT COUNT(*) FROM tb_voucher_order WHERE id=%s;" % second) == "1"
        assert a.sql("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d;" % activity) == "1"
        assert a.redis("GET", "seckill:stock:" + str(activity)) == "1"
        eventually(lambda: int(a.redis("XPENDING", "stream.orders", "g1").splitlines()[0]) == 0, "pending ACK")
        await_metric(prom, "campushub_orders_consumer_events_total", "acknowledged")
        a.check("persistence failure stays pending; graceful worker restart recovers once on target MySQL/Redis")

        # New process counters start again, while the durable order and stock do not reset.
        eventually(lambda: metric(prom, "campushub_orders_cancellation_events_total", "already_released") == 0, "new process counter reset")
        http(base, "/shop/1")
        samples = monitoring.await_series(prom, 'campushub_shop_cache_events_total{job="campushub",outcome="first_hit"} > 0')
        assert samples
        all_samples = monitoring.query(prom, '{__name__=~"campushub_.*",job="campushub"}')
        assert all_samples
        for sample in all_samples:
            assert set(sample["metric"]) == {"__name__", "application", "outcome", "instance", "job"}
        encoded = json.dumps(all_samples)
        for private in (alice, bob, alice_code, bob_code, "13900000001", "13900000002", order, second,
                        a.ENV["DB_PASSWORD"], a.ENV["REDIS_PASSWORD"]):
            assert private not in encoded, "private fixture appeared in business metric samples"
        a.check("actual Prometheus fixed-label business series, privacy and process-local reset semantics")

        a.sql("UPDATE tb_user SET status='DISABLED' WHERE id=%d;" % bob_id)
        http(base, "/user/me", bob, expected=401)
        http(base, "/user/logout", alice, method="POST")
        http(base, "/user/me", alice, expected=401)
        a.check("disabled account invalidation and logout close real sessions")
        versions = {"mysql": a.sql("SELECT VERSION();"), "redis": next(line.split(":", 1)[1].strip()
                    for line in a.redis("INFO", "server").splitlines() if line.startswith("redis_version:")),
                    "prometheus": a.compose("exec", "-T", "prometheus", "/bin/prometheus", "--version").splitlines()[0]}
        report = {"checks": a.PASSED, "versions": versions, "project": a.PROJECT,
                  "scope": "synthetic target-version HTTP/fault/metrics acceptance; accelerated lease expiry; graceful restart; not load/HA"}
        result = a.ARTIFACTS / "business-diagnostics-result.json"
        result.write_text(json.dumps(report, indent=2)); os.chmod(result, 0o600)
        print("Verified dependency versions: " + json.dumps(versions), flush=True)
    finally:
        a.cleanup()
    print("Private acceptance artifacts: " + str(a.ARTIFACTS), flush=True)


if __name__ == "__main__":
    main()
