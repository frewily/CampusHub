#!/usr/bin/env python3
"""Own a fresh local Compose project, exercise real HTTP, then remove only its synthetic resources."""
import binascii
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "campushub-phase5-test-" + uuid.uuid4().hex[:12]
ARTIFACTS = Path(tempfile.mkdtemp(prefix="campushub-phase5-acceptance-"))
os.chmod(ARTIFACTS, 0o700)
ENV = os.environ.copy()
for key in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES"):
    ENV.pop(key, None)
ENV.update(DB_USERNAME="campushub", DB_PASSWORD="synthetic-only-db-password",
           MYSQL_ROOT_PASSWORD="synthetic-only-root-password", REDIS_PASSWORD="synthetic-only-redis-password",
           MYSQL_PUBLISHED_PORT="0", REDIS_PUBLISHED_PORT="0", APP_PUBLISHED_PORT="0",
           ORDER_STREAM_CONSUMER_ENABLED="true", ORDER_CLAIM_IDLE_MS="60000", ORDER_MAX_ATTEMPTS="5",
           ORDER_CANCELLATION_RECONCILER_ENABLED="true", SHOP_CACHE_INVALIDATION_WORKER_ENABLED="true")
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / ".env.example"), "-f", str(ROOT / "compose.yaml"), "-p", PROJECT]
COUNTER = 0
PASSED = []


def run(args, data=None, timeout=120, expect_failure=False):
    global COUNTER
    COUNTER += 1
    result = subprocess.run(args, cwd=ROOT, env=ENV, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    log = ARTIFACTS / ("command-%02d.log" % COUNTER)
    with log.open("wb") as out:
        out.write(result.stdout + result.stderr)
    log.chmod(0o600)
    if expect_failure:
        assert result.returncode != 0, "expected operation to be rejected"
    elif result.returncode:
        raise RuntimeError("Operation failed; private diagnostic log: " + str(log))
    return result.stdout.decode("utf-8", "replace").strip()


def compose(*args, **kwargs):
    return run(COMPOSE + list(args), **kwargs)


def sql(statement):
    return compose("exec", "-T", "mysql", "sh", "-ec",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=socket -uroot campushub --batch --skip-column-names',
                   data=statement.encode("utf-8"))


def redis(*args):
    return compose("exec", "-T", "redis", "sh", "-ec",
                   'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli "$@"', "redis-cli", *args)


def request(base, route, expected=200, method="GET", body=None, headers=None):
    req = urllib.request.Request(base + route, data=body, headers=headers or {}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == expected, "unexpected HTTP status %s (expected %s) for %s" % (
            response.status, expected, route.split("?")[0])
        return response.read(), dict(response.headers)


def wait_ready(base):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            body, _ = request(base, "/health/ready")
            if json.loads(body)["status"] == "UP":
                return
        except (OSError, AssertionError):
            pass
        time.sleep(1)
    raise RuntimeError("private application did not become ready")


def app_base():
    port = compose("port", "app", "8081")
    assert re.fullmatch(r"127\.0\.0\.1:[0-9]+", port), "app must bind localhost only"
    return "http://" + port


def verify_host_and_prod():
    # Keep inherited application settings/credentials out of these child processes.
    host_env = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if key in os.environ}
    java = str(Path(host_env["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in host_env else "java"
    version = subprocess.run([java, "-version"], env=host_env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, timeout=10)
    assert version.returncode == 0 and b'version "1.8.' in version.stdout, "host acceptance requires Java 8"
    jar = str(ROOT / "target/campushub-0.0.1-SNAPSHOT.jar")
    host_env.update(SPRING_PROFILES_ACTIVE="dev", SERVER_PORT="0", MANAGEMENT_PORT="0", SERVER_ADDRESS="127.0.0.1",
                    DB_URL="jdbc:mysql://" + compose("port", "mysql", "3306")
                    + "/campushub?useSSL=false&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true",
                    DB_USERNAME=ENV["DB_USERNAME"], DB_PASSWORD=ENV["DB_PASSWORD"],
                    REDIS_HOST="127.0.0.1", REDIS_PORT=compose("port", "redis", "6379").split(":")[1],
                    REDIS_PASSWORD=ENV["REDIS_PASSWORD"], IMAGE_STORAGE_DIR=str(ARTIFACTS / "host-uploads"))
    log = ARTIFACTS / "host-app.log"
    with log.open("wb") as output:
        log.chmod(0o600)
        process = subprocess.Popen([java, "-jar", jar], cwd=ROOT, env=host_env, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                assert process.poll() is None, "host app exited; see private host-app.log"
                match = re.search(r"Tomcat started on port\(s\): ([0-9]+)", log.read_text("utf-8", errors="replace"))
                if match:
                    base = "http://127.0.0.1:" + match.group(1)
                    wait_ready(base)
                    run([str(ROOT / "scripts/smoke-test.sh"), base], timeout=45)
                    break
                time.sleep(1)
            else:
                raise RuntimeError("host app did not start; see private host-app.log")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=10)
    check("host JDK 8 packaged app uses private Compose dependencies and passes smoke test")
    prod_env = {key: host_env[key] for key in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if key in host_env}
    prod_env["SPRING_PROFILES_ACTIVE"] = "prod"
    result = subprocess.run([java, "-jar", jar], cwd=ROOT, env=prod_env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=30)
    log = ARTIFACTS / "prod-missing-config.log"
    log.write_bytes(result.stdout); log.chmod(0o600)
    assert result.returncode != 0 and b"prod requires spring.datasource.url" in result.stdout
    assert b"Redisson " not in result.stdout and b"HikariPool-1 - Starting" not in result.stdout
    check("packaged prod app rejects missing configuration before network clients start")


def png():
    def chunk(kind, payload):
        return struct.pack("!I", len(payload)) + kind + payload + struct.pack("!I", binascii.crc32(kind + payload) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00")) + chunk(b"IEND", b"")


def multipart(content):
    boundary = "campushub-synthetic-boundary"
    payload = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="../../unsafe.svg"'
               + "\r\nContent-Type: image/svg+xml\r\n\r\n").encode() + content + ("\r\n--" + boundary + "--\r\n").encode()
    return payload, {"Content-Type": "multipart/form-data; boundary=" + boundary}


def check(label):
    PASSED.append(label)
    print("PASS: " + label, flush=True)


def verify_campus_posts(base, tokens):
    """Synthetic HTTP -> real DB/Redis acceptance, not a load or delivery guarantee."""
    sql("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(900002,900001);")
    payload = {"title": "synthetic campus post", "images": "/imgs/synthetic.png",
               "content": "synthetic campus content", "id": 999999, "userId": 900002,
               "liked": 99, "comments": 88}
    headers = {"Content-Type": "application/json", "authorization": tokens[0]}
    body = json.dumps(payload).encode()
    request(base, "/blog", 401, "POST", body, {"Content-Type": "application/json"})
    assert sql("SELECT COUNT(*) FROM tb_blog;") == "0"
    response, _ = request(base, "/blog", 200, "POST", body, headers)
    first = json.loads(response)["data"]
    assert type(first) is int and first > 0 and first != 999999
    assert sql("SELECT COUNT(*) FROM tb_blog WHERE id=%d AND shop_id IS NULL "
               "AND user_id=900001 AND liked=0 AND comments=0;" % first) == "1"
    detail, _ = request(base, "/blog/%d" % first, headers=headers)
    detail = json.loads(detail)["data"]
    assert detail["id"] == first and detail["userId"] == 900001 and "shopId" not in detail
    assert redis("ZSCORE", "feed:900002", str(first)) != ""
    feed, _ = request(base, "/blog/of/follow?lastId=%d&offset=0" % (int(time.time() * 1000) + 60000),
                      headers={"authorization": tokens[1]})
    assert [row["id"] for row in json.loads(feed)["data"]["list"]] == [first]
    ids = {first}
    for shop_id in (None, 1):
        payload["shopId"] = shop_id
        response, _ = request(base, "/blog", 200, "POST", json.dumps(payload).encode(), headers)
        post_id = json.loads(response)["data"]
        assert type(post_id) is int and post_id > 0
        assert post_id not in ids
        ids.add(post_id)
        stored = sql("SELECT COALESCE(CAST(shop_id AS CHAR),'NULL') FROM tb_blog WHERE id=%d;" % post_id)
        assert stored == ("NULL" if shop_id is None else "1")
    for route in ("/blog/of/me", "/blog/hot"):
        response, _ = request(base, route, headers=headers)
        assert {row["id"] for row in json.loads(response)["data"]} == ids
    for shop_id in (0, -1):
        payload["shopId"] = shop_id
        response, _ = request(base, "/blog", 400, "POST", json.dumps(payload).encode(), headers)
        assert json.loads(response)["errorCode"] == "VALIDATION_FAILED"
    assert sql("SELECT COUNT(*) FROM tb_blog;") == "3"
    check("campus posts omit/null store, legacy store publication, author whitelist, reads/Feed and invalid IDs")


def verify_first_level_comments(base, tokens):
    """Own synthetic rows/triggers only; SQL injection and transaction failure are deliberate test inputs."""
    blog_id = int(sql("SELECT MIN(id) FROM tb_blog;"))
    route = "/blog-comments/of/blog/%d" % blog_id
    headers = {"Content-Type": "application/json", "authorization": tokens[0]}
    payload = {"blogId": blog_id, "content": "synthetic <script>text</script> ' ; --",
               "id": 999999, "userId": 900002, "parentId": 123, "answerId": 456,
               "liked": 99, "status": 2, "createTime": "2000-01-01T00:00:00"}
    request(base, "/blog-comments", 401, "POST", json.dumps(payload).encode(), {"Content-Type": "application/json"})
    request(base, route, 401)
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == "0"
    ids = []
    for user_id, token in ((900001, tokens[0]), (900002, tokens[1])):
        result, _ = request(base, "/blog-comments", 200, "POST", json.dumps(payload).encode(),
                            {"Content-Type": "application/json", "authorization": token})
        item = json.loads(result)["data"]
        assert set(item) == {"id", "blogId", "userId", "content", "createTime"}
        assert item["blogId"] == str(blog_id) and item["userId"] == str(user_id)
        assert isinstance(item["id"], str) and item["id"].isdigit() and item["id"] != "999999"
        assert item["content"] == payload["content"] and item["createTime"]
        ids.append(item["id"])
        assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id=%s AND user_id=%d AND parent_id=0 "
                   "AND answer_id=0 AND liked=0 AND status=0;" % (item["id"], user_id)) == "1"
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900003,'00000000003','synthetic-merchant');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900003,'MERCHANT');")
    merchant_token = "synthetic-comments-" + uuid.uuid4().hex
    redis("HSET", "login:token:" + merchant_token, "id", "900003", "nickName", "synthetic")
    redis("EXPIRE", "login:token:" + merchant_token, "600")
    merchant_headers = {"Content-Type": "application/json", "authorization": merchant_token}
    result, _ = request(base, "/blog-comments", 200, "POST", json.dumps(payload).encode(), merchant_headers)
    ids.append(json.loads(result)["data"]["id"])
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900003;")
    request(base, "/blog-comments", 401, "POST", json.dumps(payload).encode(), merchant_headers)
    request(base, route, 401, headers=merchant_headers)
    for invalid in ({"blogId": blog_id, "content": " "}, {"blogId": 0, "content": "x"},
                    {"blogId": blog_id, "content": "x" * 256}, {"content": "x"}):
        result, _ = request(base, "/blog-comments", 400, "POST", json.dumps(invalid).encode(), headers)
        assert json.loads(result)["errorCode"] == "VALIDATION_FAILED"
    for suffix in ("?size=0", "?size=51", "?beforeId=0", "?beforeId=-1", "?size=bad"):
        request(base, route + suffix, 400, headers=headers)
    request(base, "/blog-comments/of/blog/0", 400, headers=headers)
    request(base, "/blog-comments/of/blog/999999999", 404, headers=headers)
    request(base, "/blog-comments", 404, "POST", b'{"blogId":999999999,"content":"missing"}', headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "3"
    # Make visibility exclusions explicit; neither the migration nor service silently repairs history.
    fixtures = [(9007199254740993, 0, 0, "0"), (9007199254740994, 0, 0, "1"),
                (9007199254740995, 0, 0, "2"), (9007199254740996, 0, 0, "NULL"),
                (9007199254740997, 1, 0, "0"), (9007199254740998, 0, 1, "0")]
    for comment_id, parent, answer, status in fixtures:
        sql("INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,liked,status) "
            "VALUES(%d,900001,%d,%d,%d,'synthetic historical fixture',0,%s);" %
            (comment_id, blog_id, parent, answer, status))
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "3"
    first, _ = request(base, route + "?size=2", headers=headers)
    page = json.loads(first)["data"]
    assert [item["id"] for item in page["items"]] == ["9007199254740993", ids[-1]]
    assert page["hasNext"] is True and page["nextBeforeId"] == ids[-1]
    newer, _ = request(base, "/blog-comments", 200, "POST", json.dumps(payload).encode(), headers)
    assert int(json.loads(newer)["data"]["id"]) > 9007199254740998
    last, _ = request(base, route + "?size=2&beforeId=" + page["nextBeforeId"], headers=headers)
    last = json.loads(last)["data"]
    assert [item["id"] for item in last["items"]] == ids[-2::-1]
    assert last["hasNext"] is False and "nextBeforeId" not in last
    all_rows, _ = request(base, route + "?size=50", headers=headers)
    assert len(json.loads(all_rows)["data"]["items"]) == 5
    other_blog = int(sql("SELECT MAX(id) FROM tb_blog;"))
    empty, _ = request(base, "/blog-comments/of/blog/%d" % other_blog, headers=headers)
    assert json.loads(empty)["data"] == {"items": [], "hasNext": False}
    sql("UPDATE tb_blog SET comments=NULL WHERE id=%d;" % other_blog)
    request(base, "/blog-comments", 200, "POST", json.dumps({"blogId": other_blog, "content": "null counter"}).encode(), headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % other_blog) == "1"
    check("first-level comments real roles, validation/whitelist, visibility, lossless cursor and NULL counter")

    original_count = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    original_counter = sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id)
    for name, definition in (
            ("synthetic_comment_counter_failure", "BEFORE UPDATE ON tb_blog FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private counter failure'"),
            ("synthetic_comment_insert_failure", "BEFORE INSERT ON tb_blog_comments FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private insert failure'")):
        sql("CREATE TRIGGER " + name + " " + definition + ";")
        try:
            result, _ = request(base, "/blog-comments", 500, "POST", json.dumps(payload).encode(), headers)
            result = json.loads(result)
            assert result["success"] is False and result["errorCode"] == "INTERNAL_ERROR"
            assert "synthetic private" not in json.dumps(result)
            assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == original_count
            assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == original_counter
        finally:
            sql("DROP TRIGGER " + name + ";")
    check("comment insert/counter database failures roll back both writes with generic HTTP errors")


def verify_direct_comment_replies(base, tokens):
    """Direct replies only, using the previous synthetic large-ID root and project-owned fault triggers."""
    root_id = 9007199254740993
    blog_id = int(sql("SELECT blog_id FROM tb_blog_comments WHERE id=%d;" % root_id))
    other_blog = int(sql("SELECT MAX(id) FROM tb_blog;"))
    route = "/blog-comments/%d/replies" % root_id
    headers = {"Content-Type": "application/json", "authorization": tokens[0]}
    payload = {"content": "synthetic reply <script>untrusted</script> ' ; --",
               "id": 999999, "userId": 900002, "blogId": other_blog, "parentId": 456,
               "answerId": 789, "liked": 99, "status": 2, "createTime": "2000-01-01T00:00:00"}
    first_level, _ = request(base, "/blog-comments/of/blog/%d?size=50" % blog_id, headers=headers)
    initial_roots = [item["id"] for item in json.loads(first_level)["data"]["items"]]
    initial_counter = int(sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id))
    initial_rows = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    request(base, route, 401)
    request(base, route, 401, "POST", json.dumps(payload).encode(), {"Content-Type": "application/json"})
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == initial_rows
    empty, _ = request(base, route, headers=headers)
    assert json.loads(empty)["data"] == {"items": [], "hasNext": False}

    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900004,'00000000004','synthetic-reply-merchant');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900004,'MERCHANT');")
    merchant_token = "synthetic-replies-" + uuid.uuid4().hex
    redis("HSET", "login:token:" + merchant_token, "id", "900004", "nickName", "synthetic")
    redis("EXPIRE", "login:token:" + merchant_token, "600")
    reply_ids = []
    for user_id, token in ((900001, tokens[0]), (900002, tokens[1]), (900004, merchant_token)):
        own_headers = {"Content-Type": "application/json", "authorization": token}
        result, _ = request(base, route, 200, "POST", json.dumps(payload).encode(), own_headers)
        item = json.loads(result)["data"]
        assert set(item) == {"id", "blogId", "userId", "content", "createTime"}
        assert item["blogId"] == str(blog_id) and item["userId"] == str(user_id)
        assert isinstance(item["id"], str) and item["id"].isdigit() and int(item["id"]) > root_id
        assert item["content"] == payload["content"] and item["createTime"]
        reply_ids.append(item["id"])
        assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id=%s AND blog_id=%d AND user_id=%d "
                   "AND parent_id=%d AND answer_id=%d AND liked=0 AND status=0;" %
                   (item["id"], blog_id, user_id, root_id, root_id)) == "1"
        request(base, route, headers=own_headers)
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900004;")
    disabled = {"Content-Type": "application/json", "authorization": merchant_token}
    request(base, route, 401, "POST", json.dumps(payload).encode(), disabled)
    request(base, route, 401, headers=disabled)
    for body in (b"null", b"{", b"{}", b'{"content":" "}', json.dumps({"content": "x" * 256}).encode()):
        result, _ = request(base, route, 400, "POST", body, headers)
        assert json.loads(result)["errorCode"] == "VALIDATION_FAILED"
    for invalid_path in ("0", "-1", "bad", "9223372036854775808"):
        path = "/blog-comments/" + invalid_path + "/replies"
        request(base, path, 400, "POST", json.dumps(payload).encode(), headers)
        request(base, path, 400, headers=headers)
    for suffix in ("?size=0", "?size=51", "?beforeId=0", "?beforeId=-1", "?beforeId=bad"):
        request(base, route + suffix, 400, headers=headers)

    for status in ("1", "2", "NULL"):
        sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
            "VALUES(900001,%d,%d,%d,'synthetic excluded reply',%s);" % (blog_id, root_id, root_id, status))
    sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
        "VALUES(900001,%d,%d,1,'synthetic wrong answer',0),"
        "(900001,%d,1,%d,'synthetic other root',0),"
        "(900001,%d,%d,%d,'synthetic wrong blog',0);" %
        (blog_id, root_id, blog_id, root_id, other_blog, root_id, root_id))
    page, _ = request(base, route + "?size=2", headers=headers)
    page = json.loads(page)["data"]
    assert [item["id"] for item in page["items"]] == reply_ids[:0:-1]
    assert page["hasNext"] is True and page["nextBeforeId"] == reply_ids[1]
    newer, _ = request(base, route, 200, "POST", json.dumps(payload).encode(), headers)
    newer_id = json.loads(newer)["data"]["id"]
    final_page, _ = request(base, route + "?size=2&beforeId=" + page["nextBeforeId"], headers=headers)
    final_page = json.loads(final_page)["data"]
    assert set(final_page) == {"items", "hasNext"} and final_page["hasNext"] is False
    assert [item["id"] for item in final_page["items"]] == reply_ids[:1]
    visible, _ = request(base, route + "?size=50", headers=headers)
    assert [item["id"] for item in json.loads(visible)["data"]["items"]] == [newer_id] + reply_ids[::-1]
    first_level, _ = request(base, "/blog-comments/of/blog/%d?size=50" % blog_id, headers=headers)
    assert [item["id"] for item in json.loads(first_level)["data"]["items"]] == initial_roots
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(initial_counter + 4)
    rows_before_invalid = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    for invalid_root in (999999, 9007199254740994, 9007199254740995, 9007199254740996,
                         9007199254740997, 9007199254740998, int(reply_ids[0])):
        path = "/blog-comments/%d/replies" % invalid_root
        request(base, path, 404, "POST", json.dumps(payload).encode(), headers)
        request(base, path, 404, headers=headers)
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == rows_before_invalid
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(initial_counter + 4)
    # A root without a post is not a usable reply target either.
    sql("INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,status) "
        "VALUES(999999,900001,999999999,0,0,'synthetic orphan root',0);")
    request(base, "/blog-comments/999999/replies", 404, "POST", json.dumps(payload).encode(), headers)
    request(base, "/blog-comments/999999/replies", 404, headers=headers)
    # Synthetic external moderation; no new moderation endpoint or automatic data cleanup.
    sql("UPDATE tb_blog_comments SET status=2 WHERE id=%d;" % root_id)
    request(base, route, 404, "POST", json.dumps(payload).encode(), headers)
    request(base, route, 404, headers=headers)
    sql("UPDATE tb_blog_comments SET status=0 WHERE id=%d;" % root_id)
    check("direct replies real roles/whitelist, visible root, same-post relation, large-ID cursor and first-level isolation")

    # Compatibility with a legacy NULL aggregate counter, without historical recounting.
    sql("UPDATE tb_blog SET comments=NULL WHERE id=%d;" % blog_id)
    request(base, route, 200, "POST", b'{"content":"NULL counter reply"}', headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "1"
    original_count = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    for name, definition in (
            ("synthetic_reply_counter_failure", "BEFORE UPDATE ON tb_blog FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private reply counter failure'"),
            ("synthetic_reply_insert_failure", "BEFORE INSERT ON tb_blog_comments FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private reply insert failure'")):
        sql("CREATE TRIGGER " + name + " " + definition + ";")
        try:
            result, _ = request(base, route, 500, "POST", json.dumps(payload).encode(), headers)
            result = json.loads(result)
            assert result["success"] is False and result["errorCode"] == "INTERNAL_ERROR"
            assert "synthetic private" not in json.dumps(result)
            assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == original_count
            assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "1"
        finally:
            sql("DROP TRIGGER " + name + ";")
    check("direct reply NULL counter and insert/counter faults are atomic with generic HTTP errors")


def cleanup():
    try:
        compose("--profile", "app", "logs", "--no-color", timeout=30)
    finally:
        compose("--profile", "app", "down", "--volumes", "--rmi", "local", "--remove-orphans", timeout=90)
        assert compose("--profile", "app", "ps", "-aq") == "", "test containers remain after cleanup"
        for kind in ("volume", "network"):
            assert run(["docker", kind, "ls", "--filter", "label=com.docker.compose.project=" + PROJECT,
                        "--format", "{{.Name}}"] ) == "", "test " + kind + " resources remain after cleanup"
        assert run(["docker", "image", "ls", "--filter", "reference=" + PROJECT + "-app:latest",
                    "--format", "{{.Repository}}"] ) == "", "test app image remains after cleanup"
        print("Removed only test-owned containers, network, image and synthetic volumes: " + PROJECT, flush=True)


def main():
    if ENV.get("DOCKER_HOST") and not ENV["DOCKER_HOST"].startswith("unix://"):
        raise RuntimeError("acceptance rejects non-local DOCKER_HOST overrides")
    endpoint = run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])
    if not endpoint.startswith("unix://"):
        raise RuntimeError("acceptance requires a local Unix-socket Docker engine")
    if not (ROOT / "target/campushub-0.0.1-SNAPSHOT.jar").is_file():
        raise RuntimeError("Build the JDK 8 package first")
    assert compose("ps", "-aq") == "", "project must be fresh"
    assert run(["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + PROJECT,
                "--format", "{{.Name}}"] ) == "", "project volumes must be fresh"
    owned = True
    try:
        compose("--profile", "app", "up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=240)
        base = app_base()
        assert compose("exec", "-T", "app", "id", "-u") == "10001"
        run([str(ROOT / "scripts/smoke-test.sh"), base], timeout=45)
        check("fresh bootstrap, packaged non-root app, health and seeded search")
        body, _ = request(base, "/shop/1")
        assert json.loads(body)["success"] is True
        check("real MySQL and authenticated Redis shop detail")
        for migration in sorted((ROOT / "src/main/resources/db/migration").glob("V*.sql")):
            sql(migration.read_text("utf-8"))
        assert sql("SELECT COUNT(*) FROM tb_shop;") == "1"
        compose("exec", "-T", "mysql", "sh", "/docker-entrypoint-initdb.d/00-bootstrap.sh", expect_failure=True)
        assert sql("SELECT COUNT(*) FROM tb_shop;") == "1"
        assert sql("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                   "AND TABLE_NAME='tb_blog' AND COLUMN_NAME='shop_id';") == "YES"
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' "
                   "AND INDEX_NAME='idx_blog_comments_page';") == "blog_id,parent_id,answer_id,status,id"
        check("V001-V007 repeat, nullable post store, comment index and populated-database bootstrap refusal")
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900001,'00000000001','synthetic-user'),(900002,'00000000002','synthetic-admin');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(900001,'USER'),(900002,'ADMIN');")
        tokens = ["synthetic-phase5-" + uuid.uuid4().hex for _ in range(2)]
        for user, token in zip((900001, 900002), tokens):
            redis("HSET", "login:token:" + token, "id", str(user), "nickName", "synthetic")
            redis("EXPIRE", "login:token:" + token, "600")
        verify_campus_posts(base, tokens)
        verify_first_level_comments(base, tokens)
        verify_direct_comment_replies(base, tokens)
        upload, headers = multipart(png())
        request(base, "/upload/blog", 401, "POST", upload, headers)
        headers["authorization"] = tokens[0]
        body, _ = request(base, "/upload/blog", 200, "POST", upload, headers)
        name = json.loads(body)["data"]
        assert re.fullmatch(r"/blogs/[0-9]+/[0-9]+/[0-9a-f-]{36}\.png", name)
        body, image_headers = request(base, "/imgs" + name)
        assert body.startswith(b"\x89PNG") and image_headers["X-Content-Type-Options"] == "nosniff"
        assert image_headers["Content-Type"] == "image/png"
        check("anonymous upload rejected; USER uploads canonical PNG and public read is safe")
        compose("restart", "app")
        base = app_base() # Docker may reassign a random published port on restart.
        wait_ready(base)
        preserved, _ = request(base, "/imgs" + name)
        assert preserved == body
        check("image volume survives app restart")
        delete = "/upload/blog/delete?" + urllib.parse.urlencode({"name": name})
        request(base, delete, 403, headers={"authorization": tokens[0]})
        request(base, "/upload/blog/delete?name=..%2Foutside", 400, headers={"authorization": tokens[1]})
        request(base, delete, 200, headers={"authorization": tokens[1]})
        request(base, "/imgs" + name, 404)
        for invalid in (b"<svg onload='synthetic' />", b"x" * (2097152 + 1)):
            payload, invalid_headers = multipart(invalid); invalid_headers["authorization"] = tokens[0]
            request(base, "/upload/blog", 400, "POST", payload, invalid_headers)
        check("actual method security, controlled admin deletion, corrupt and oversized upload rejection")
        sql("UPDATE tb_shop SET name='Phase5 preserved row' WHERE id=1;")
        compose("restart", "mysql")
        wait_ready(base)
        assert sql("SELECT name FROM tb_shop WHERE id=1;") == "Phase5 preserved row"
        check("database restart preserves data rather than replaying seed")
        compose("stop", "redis")
        body, _ = request(base, "/health/ready", 503); assert json.loads(body) == {"status": "DOWN"}
        body, _ = request(base, "/health/live"); assert json.loads(body) == {"status": "UP"}
        compose("up", "-d", "--wait", "--wait-timeout", "60", "redis")
        wait_ready(base)
        assert redis("EXISTS", "login:token:" + tokens[0]) == "1"
        check("dependency outage is 503 without details; Redis AOF session survives restart")
        verify_host_and_prod()
        versions = {"mysql": sql("SELECT VERSION();"), "redis": redis("INFO", "server").split("redis_version:")[1].splitlines()[0]}
        report = {"checks": PASSED, "versions": versions, "scope": "fresh synthetic local Compose project; no production or load-test claim"}
        with (ARTIFACTS / "result.json").open("w") as out:
            json.dump(report, out, ensure_ascii=False, indent=2)
        print("Verified versions: " + json.dumps(versions), flush=True)
    finally:
        if owned:
            cleanup()
    print("Private acceptance artifacts: " + str(ARTIFACTS), flush=True)


if __name__ == "__main__":
    main()
