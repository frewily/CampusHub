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


def verify_text_only_campus_posts(base, tokens):
    """Optional campus images only; keep legacy store images required and old read shapes."""
    sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES"
        "(900020,'00000000020','synthetic-text-merchant','ACTIVE'),"
        "(900021,'00000000021','synthetic-text-follower','ACTIVE'),"
        "(900022,'00000000022','synthetic-text-disabled','DISABLED'),"
        "(900023,'00000000023','synthetic-text-roleless','ACTIVE'),"
        "(900024,'00000000024','synthetic-text-unknown','ACTIVE');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900020,'MERCHANT'),(900021,'USER'),"
        "(900022,'USER'),(900024,'AUDITOR');"
        "INSERT INTO tb_follow(user_id,follow_user_id) VALUES"
        "(900021,900001),(900021,900002),(900021,900020);")
    headers_by_user = {900001: {"authorization": tokens[0], "Content-Type": "application/json"},
                       900002: {"authorization": tokens[1], "Content-Type": "application/json"}}
    for user_id in (900020, 900021, 900022, 900023, 900024):
        token = "synthetic-text-post-" + uuid.uuid4().hex
        redis("HSET", "login:token:" + token, "id", str(user_id), "nickName", "synthetic")
        redis("EXPIRE", "login:token:" + token, "600")
        headers_by_user[user_id] = {"authorization": token, "Content-Type": "application/json"}
    base_payload = {"title": "synthetic text-only campus post", "content": "synthetic plain campus content",
                    "id": 999999, "userId": 900021, "liked": 99, "comments": 88,
                    "createTime": "2000-01-01T00:00:00", "name": "untrusted display name"}
    count_before = int(sql("SELECT COUNT(*) FROM tb_blog;"))
    request(base, "/blog", 401, "POST", json.dumps(base_payload).encode(), {"Content-Type": "application/json"})
    for user_id in (900022, 900023, 900024):
        request(base, "/blog", 401, "POST", json.dumps(base_payload).encode(), headers_by_user[user_id])
    assert int(sql("SELECT COUNT(*) FROM tb_blog;")) == count_before
    cases = [(900001, {}), (900001, {"shopId": None, "images": None}),
             (900020, {"images": ""}), (900002, {"shopId": None, "images": " \t\r\n"})]
    ids = []
    for user_id, fields in cases:
        payload = dict(base_payload, **fields)
        body, _ = request(base, "/blog", 200, "POST", json.dumps(payload).encode(), headers_by_user[user_id])
        post_id = json.loads(body)["data"]
        assert type(post_id) is int and post_id > 0 and post_id != 999999
        ids.append(post_id)
        assert sql("SELECT COUNT(*) FROM tb_blog WHERE id=%d AND shop_id IS NULL AND images='' "
                   "AND user_id=%d AND liked=0 AND comments=0;" % (post_id, user_id)) == "1"
        detail, _ = request(base, "/blog/%d" % post_id, headers=headers_by_user[900001])
        detail = json.loads(detail)["data"]
        assert detail["images"] == "" and "shopId" not in detail
        assert detail["userId"] == user_id and detail["content"] == base_payload["content"]
        assert detail["name"] != "untrusted display name"
        assert redis("ZSCORE", "feed:900021", str(post_id)) != ""
    for user_id in (900001, 900002, 900020):
        for route in ("/blog/of/me", "/blog/of/user?id=%d" % user_id):
            body, _ = request(base, route, headers=headers_by_user[user_id])
            rows = {row["id"]: row for row in json.loads(body)["data"]}
            for post_id in ids:
                if post_id in rows:
                    assert rows[post_id]["images"] == ""
            assert any(post_id in rows for post_id in ids)
    body, _ = request(base, "/blog/hot", headers=headers_by_user[900001])
    rows = {row["id"]: row for row in json.loads(body)["data"]}
    assert all(post_id in rows and rows[post_id]["images"] == "" for post_id in ids)
    body, _ = request(base, "/blog/of/follow?lastId=%d&offset=0" % (int(time.time() * 1000) + 60000),
                      headers=headers_by_user[900021])
    feed_rows = json.loads(body)["data"]["list"]
    assert len(feed_rows) == 2 and all(row["id"] in ids and row["images"] == "" for row in feed_rows)
    maximum = dict(base_payload, images="p" * 2048)
    body, _ = request(base, "/blog", 200, "POST", json.dumps(maximum).encode(), headers_by_user[900001])
    maximum_id = json.loads(body)["data"]
    body, _ = request(base, "/blog/%d" % maximum_id, headers=headers_by_user[900001])
    assert json.loads(body)["data"]["images"] == maximum["images"]
    invalids = [dict(base_payload, shopId=1, storeImageValid=True),
                dict(base_payload, shopId=1, images=None), dict(base_payload, shopId=1, images=""),
                dict(base_payload, shopId=1, images=" \t"), dict(base_payload, shopId=0),
                dict(base_payload, images=" " * 2049), dict(base_payload, images="p" * 2049),
                dict(base_payload, title=" "), dict(base_payload, title="t" * 256),
                dict(base_payload, content=" "), dict(base_payload, content="c" * 2049)]
    for payload in invalids:
        body, _ = request(base, "/blog", 400, "POST", json.dumps(payload).encode(), headers_by_user[900001])
        assert json.loads(body)["errorCode"] == "VALIDATION_FAILED"
    assert int(sql("SELECT COUNT(*) FROM tb_blog;")) == count_before + 5
    check("text-only campus posts: omitted/null/empty/trim-blank images, three roles, session-author whitelist, legacy store rejection, length bounds, empty-string DB/read/Feed compatibility")


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


def verify_reply_withdrawal(base, tokens):
    """Author-only logical removal, in this project's synthetic database; never delete content rows."""
    root_id = 9007199254740993
    blog_id = int(sql("SELECT blog_id FROM tb_blog_comments WHERE id=%d;" % root_id))
    reply_route = "/blog-comments/%d/replies" % root_id
    user_headers = {"authorization": tokens[0], "Content-Type": "application/json"}
    admin_headers = {"authorization": tokens[1], "Content-Type": "application/json"}
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900005,'00000000005','synthetic-withdraw-merchant');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900005,'MERCHANT');")
    merchant_token = "synthetic-withdraw-" + uuid.uuid4().hex
    redis("HSET", "login:token:" + merchant_token, "id", "900005", "nickName", "synthetic")
    redis("EXPIRE", "login:token:" + merchant_token, "600")
    merchant_headers = {"authorization": merchant_token, "Content-Type": "application/json"}
    targets = []
    for headers in (user_headers, admin_headers, merchant_headers):
        result, _ = request(base, reply_route, 200, "POST", b'{"content":"retained withdrawal content"}', headers)
        targets.append(json.loads(result)["data"]["id"])
    route = "/blog-comments/replies/" + targets[0]
    before = int(sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id))
    rows = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    request(base, route, 401, "DELETE")
    request(base, route, 404, "DELETE", headers=admin_headers)  # no administrator override
    for invalid in ("0", "-1", "bad", "9223372036854775808"):
        request(base, "/blog-comments/replies/" + invalid, 400, "DELETE", headers=user_headers)
    for invalid in ("999999999", str(root_id)):
        request(base, "/blog-comments/replies/" + invalid, 404, "DELETE", headers=user_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(before)
    for reply_id, headers in zip(targets, (user_headers, admin_headers, merchant_headers)):
        own_route = "/blog-comments/replies/" + reply_id
        for _ in range(2):
            result, _ = request(base, own_route, 200, "DELETE", headers=headers)
            assert json.loads(result) == {"success": True}
        assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % reply_id) == "3"
        assert sql("SELECT content FROM tb_blog_comments WHERE id=%s;" % reply_id) == "retained withdrawal content"
    request(base, route, 404, "DELETE", headers=admin_headers)  # still author-only after withdrawal
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(before - 3)
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == rows
    page, _ = request(base, reply_route + "?size=50", headers=user_headers)
    assert not set(targets).intersection(item["id"] for item in json.loads(page)["data"]["items"])
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900005;")
    request(base, "/blog-comments/replies/" + targets[2], 401, "DELETE", headers=merchant_headers)
    # The parent no longer being readable must not prevent withdrawal of one's own normal reply.
    result, _ = request(base, reply_route, 200, "POST", b'{"content":"hidden root withdrawal"}', user_headers)
    hidden_target = json.loads(result)["data"]["id"]
    sql("UPDATE tb_blog_comments SET status=2 WHERE id=%d;" % root_id)
    request(base, "/blog-comments/replies/" + hidden_target, 200, "DELETE", headers=user_headers)
    sql("UPDATE tb_blog_comments SET status=0 WHERE id=%d;" % root_id)
    for status, parent, answer, owner_blog in (("1", root_id, root_id, blog_id),
            ("2", root_id, root_id, blog_id), ("NULL", root_id, root_id, blog_id),
            ("0", root_id, root_id + 1000, blog_id), ("0", root_id, root_id, 999999999)):
        target = sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
                     "VALUES(900001,%d,%d,%d,'synthetic excluded withdrawal',%s); SELECT LAST_INSERT_ID();" %
                     (owner_blog, parent, answer, status))
        request(base, "/blog-comments/replies/" + target, 404, "DELETE", headers=user_headers)
        assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id=%s AND status=3;" % target) == "0"
    check("author-only reply withdrawal: three roles, no admin override, repeat success, retained rows, hidden parent and list exclusion")

    for counter in ("NULL", "0"):
        result, _ = request(base, reply_route, 200, "POST", b'{"content":"legacy counter withdrawal"}', user_headers)
        target = json.loads(result)["data"]["id"]
        sql("UPDATE tb_blog SET comments=%s WHERE id=%d;" % (counter, blog_id))
        request(base, "/blog-comments/replies/" + target, 200, "DELETE", headers=user_headers)
        request(base, "/blog-comments/replies/" + target, 200, "DELETE", headers=user_headers)
        assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
    result, _ = request(base, reply_route, 200, "POST", b'{"content":"atomic withdrawal failure"}', user_headers)
    target = json.loads(result)["data"]["id"]
    fault_route = "/blog-comments/replies/" + target
    for name, definition, expected, code in (
            ("synthetic_withdraw_row_failure", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private withdrawal failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_withdraw_counter_failure", "BEFORE UPDATE ON tb_blog FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private withdrawal count failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_withdraw_zero_counter", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "DELETE FROM tb_blog WHERE id=NEW.blog_id", 422, "OPERATION_FAILED")):
        sql("CREATE TRIGGER " + name + " " + definition + ";")
        try:
            result, _ = request(base, fault_route, expected, "DELETE", headers=user_headers)
            result = json.loads(result)
            assert result["success"] is False and result["errorCode"] == code
            assert "synthetic private" not in json.dumps(result)
            assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % target) == "0"
            assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "1"
        finally:
            sql("DROP TRIGGER " + name + ";")
    request(base, fault_route, 200, "DELETE", headers=user_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
    check("reply withdrawal NULL/zero clamp and real row/counter/zero-row faults roll back with typed generic HTTP errors")


def verify_first_level_withdrawal(base, tokens):
    """Root withdrawal preserves descendants; real HTTP verifies author-only rights and atomicity."""
    blog_id = int(sql("SELECT blog_id FROM tb_blog_comments WHERE id=9007199254740993;"))
    user_headers = {"authorization": tokens[0], "Content-Type": "application/json"}
    admin_headers = {"authorization": tokens[1], "Content-Type": "application/json"}
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900006,'00000000006','synthetic-root-withdraw-merchant');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900006,'MERCHANT');")
    merchant_token = "synthetic-root-withdraw-" + uuid.uuid4().hex
    redis("HSET", "login:token:" + merchant_token, "id", "900006", "nickName", "synthetic")
    redis("EXPIRE", "login:token:" + merchant_token, "600")
    merchant_headers = {"authorization": merchant_token, "Content-Type": "application/json"}

    def create_root(headers):
        payload = json.dumps({"blogId": str(blog_id), "content": "retained root withdrawal content"}).encode()
        result, _ = request(base, "/blog-comments", 200, "POST", payload, headers)
        return json.loads(result)["data"]["id"]

    roots = [create_root(headers) for headers in (user_headers, admin_headers, merchant_headers)]
    reply_route = "/blog-comments/" + roots[0] + "/replies"
    result, _ = request(base, reply_route, 200, "POST", b'{"content":"preserved child withdrawal content"}', admin_headers)
    child = json.loads(result)["data"]["id"]
    route = "/blog-comments/" + roots[0]
    counter = int(sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id))
    rows = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    request(base, route, 401, "DELETE")
    request(base, route, 404, "DELETE", headers=admin_headers)
    request(base, "/blog-comments/" + roots[1], 404, "DELETE", headers=user_headers)
    request(base, "/blog-comments/" + child, 404, "DELETE", headers=admin_headers)
    for invalid in ("0", "-1", "bad", "9223372036854775808"):
        request(base, "/blog-comments/" + invalid, 400, "DELETE", headers=user_headers)
    for status, parent, answer, owner_blog in (("1", 0, 0, blog_id), ("2", 0, 0, blog_id),
            ("NULL", 0, 0, blog_id), ("0", 0, int(roots[0]), blog_id),
            ("0", int(roots[0]), 0, blog_id), ("0", 0, 0, 999999999)):
        target = sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
                     "VALUES(900001,%d,%d,%d,'synthetic excluded root withdrawal',%s); SELECT LAST_INSERT_ID();" %
                     (owner_blog, parent, answer, status))
        request(base, "/blog-comments/" + target, 404, "DELETE", headers=user_headers)
        assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id=%s AND status=3;" % target) == "0"
    request(base, "/blog-comments/999999999", 404, "DELETE", headers=user_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter)
    # Legacy exclusion fixtures add rows, but no failed HTTP operation should alter them or other content.
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == str(int(rows) + 6)
    for root, headers in zip(roots, (user_headers, admin_headers, merchant_headers)):
        for _ in range(2):
            result, _ = request(base, "/blog-comments/" + root, 200, "DELETE", headers=headers)
            assert json.loads(result) == {"success": True}
        assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % root) == "3"
        assert sql("SELECT content FROM tb_blog_comments WHERE id=%s;" % root) == "retained root withdrawal content"
    request(base, route, 404, "DELETE", headers=admin_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter - 3)
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == str(int(rows) + 6)
    assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % child) == "0"
    assert sql("SELECT content FROM tb_blog_comments WHERE id=%s;" % child) == "preserved child withdrawal content"
    request(base, reply_route, 404, headers=user_headers)
    request(base, reply_route, 404, "POST", b'{"content":"must not be created"}', admin_headers)
    request(base, "/blog-comments/replies/" + child, 404, "DELETE", headers=user_headers)
    request(base, "/blog-comments/replies/" + child, 200, "DELETE", headers=admin_headers)
    request(base, "/blog-comments/replies/" + child, 200, "DELETE", headers=admin_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter - 4)
    page, _ = request(base, "/blog-comments/of/blog/%d?size=50" % blog_id, headers=user_headers)
    assert not set(roots).intersection(item["id"] for item in json.loads(page)["data"]["items"])
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900006;")
    request(base, "/blog-comments/" + roots[2], 401, "DELETE", headers=merchant_headers)
    check("first-level author withdrawal: three roles, no admin override, retained descendants, closed reply read/create and independent child withdrawal")

    for legacy in ("NULL", "0"):
        target = create_root(user_headers)
        sql("UPDATE tb_blog SET comments=%s WHERE id=%d;" % (legacy, blog_id))
        request(base, "/blog-comments/" + target, 200, "DELETE", headers=user_headers)
        request(base, "/blog-comments/" + target, 200, "DELETE", headers=user_headers)
        assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
    target = create_root(user_headers)
    child_route = "/blog-comments/" + target + "/replies"
    result, _ = request(base, child_route, 200, "POST", b'{"content":"child survives fault"}', admin_headers)
    child = json.loads(result)["data"]["id"]
    rows = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    for name, definition, expected, code in (
            ("synthetic_root_withdraw_failure", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private root withdrawal failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_root_counter_failure", "BEFORE UPDATE ON tb_blog FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private root counter failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_root_zero_counter", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "DELETE FROM tb_blog WHERE id=NEW.blog_id", 422, "OPERATION_FAILED")):
        sql("CREATE TRIGGER " + name + " " + definition + ";")
        try:
            result, _ = request(base, "/blog-comments/" + target, expected, "DELETE", headers=user_headers)
            result = json.loads(result)
            assert result["success"] is False and result["errorCode"] == code
            assert "synthetic private" not in json.dumps(result)
            assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id IN (%s,%s) AND status=0;" % (target, child)) == "2"
            assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "2"
            assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == rows
            page, _ = request(base, child_route, headers=admin_headers)
            assert [item["id"] for item in json.loads(page)["data"]["items"]] == [child]
        finally:
            sql("DROP TRIGGER " + name + ";")
    request(base, "/blog-comments/" + target, 200, "DELETE", headers=user_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "1"
    assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % child) == "0"
    check("first-level withdrawal NULL/zero floor and real status/counter/zero-row faults preserve root, descendants and aggregate atomically")


def verify_first_level_moderation(base, tokens):
    """Admin-only, audited root hide using real sessions, HTTP, SQL and transaction faults."""
    blog_id = int(sql("SELECT blog_id FROM tb_blog_comments WHERE id=9007199254740993;"))
    user_headers = {"authorization": tokens[0], "Content-Type": "application/json"}
    admin_headers = {"authorization": tokens[1], "Content-Type": "application/json"}
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900007,'00000000007','synthetic-moderation-merchant'),"
        "(900008,'00000000008','synthetic-second-admin'),(900009,'00000000009','synthetic-disabled-admin'),"
        "(900010,'00000000010','synthetic-unknown-role');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(900007,'MERCHANT'),(900008,'ADMIN'),(900009,'ADMIN');"
        "UPDATE tb_user SET status='DISABLED' WHERE id=900009;")
    # No valid role row for 900010: the token alone must not establish an application identity.
    headers_by_user = {}
    for user_id in (900007, 900008, 900009, 900010):
        token = "synthetic-moderation-" + uuid.uuid4().hex
        redis("HSET", "login:token:" + token, "id", str(user_id), "nickName", "synthetic")
        redis("EXPIRE", "login:token:" + token, "600")
        headers_by_user[user_id] = {"authorization": token, "Content-Type": "application/json"}

    def create_root(headers):
        payload = json.dumps({"blogId": str(blog_id), "content": "retained moderation root"}).encode()
        result, _ = request(base, "/blog-comments", 200, "POST", payload, headers)
        return json.loads(result)["data"]["id"]

    target = create_root(user_headers)
    reply_route = "/blog-comments/" + target + "/replies"
    result, _ = request(base, reply_route, 200, "POST", b'{"content":"retained moderation child"}', user_headers)
    child = json.loads(result)["data"]["id"]
    route = "/admin/blog-comments/" + target + "/hide"
    payload = b'{"reason":"synthetic moderation reason","operatorUserId":900001,"status":3,"blogId":99}'
    counter = int(sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id))
    rows = sql("SELECT COUNT(*) FROM tb_blog_comments;")
    request(base, route, 401, "POST", payload, {"Content-Type": "application/json"})
    for headers in (user_headers, headers_by_user[900007]):
        request(base, route, 403, "POST", payload, headers)
    merchant_root = create_root(headers_by_user[900007])
    request(base, "/admin/blog-comments/" + merchant_root + "/hide", 403, "POST", payload, headers_by_user[900007])
    # Account disabled and role-less synthetic sessions are unauthenticated, including a nominal admin token.
    for user_id in (900009, 900010):
        request(base, route, 401, "POST", payload, headers_by_user[user_id])
    for invalid in ("0", "-1", "bad", "9223372036854775808"):
        request(base, "/admin/blog-comments/" + invalid + "/hide", 400, "POST", payload, admin_headers)
    for body in (b"{", b"null", b"{}", b'{"reason":null}', b'{"reason":" "}',
                 json.dumps({"reason": "x" * 256}).encode()):
        request(base, route, 400, "POST", body, admin_headers)
    request(base, "/admin/blog-comments/" + child + "/hide", 404, "POST", payload, admin_headers)
    request(base, "/admin/blog-comments/999999999/hide", 404, "POST", payload, admin_headers)
    for state, parent, answer, owner_blog in (("1", 0, 0, blog_id), ("2", 0, 0, blog_id),
            ("3", 0, 0, blog_id), ("NULL", 0, 0, blog_id), ("0", 0, int(target), blog_id),
            ("0", int(target), 0, blog_id), ("0", 0, 0, 999999999)):
        excluded = sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
                       "VALUES(900001,%d,%d,%d,'synthetic excluded moderation',%s); SELECT LAST_INSERT_ID();" %
                       (owner_blog, parent, answer, state))
        request(base, "/admin/blog-comments/" + excluded + "/hide", 404, "POST", payload, admin_headers)
        assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % excluded) == "0"
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter + 1)
    for headers, body in ((admin_headers, payload), (headers_by_user[900008], b'{"reason":"different repeat reason"}')):
        result, _ = request(base, route, 200, "POST", body, headers)
        assert json.loads(result) == {"success": True}
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter)
    assert sql("SELECT COUNT(*) FROM tb_blog_comments;") == str(int(rows) + 8)
    assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % target) == "2"
    assert sql("SELECT content FROM tb_blog_comments WHERE id=%s;" % target) == "retained moderation root"
    assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % child) == "0"
    assert sql("SELECT content FROM tb_blog_comments WHERE id=%s;" % child) == "retained moderation child"
    assert sql("SELECT CONCAT(operator_user_id,':',reason,':',from_status,':',to_status) "
               "FROM tb_blog_comment_hide_audit WHERE comment_id=%s AND blog_id=%d;" % (target, blog_id)) == \
        "900002:synthetic moderation reason:0:2"
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s AND create_time IS NOT NULL;" % target) == "1"
    sql((ROOT / "src/main/resources/db/migration/V008__add_blog_comment_hide_audit.sql").read_text("utf-8"))
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % target) == "1"
    request(base, reply_route, 404, headers=user_headers)
    request(base, reply_route, 404, "POST", b'{"content":"must not insert"}', admin_headers)
    request(base, "/blog-comments/" + target, 404, "DELETE", headers=user_headers)
    request(base, "/blog-comments/replies/" + child, 200, "DELETE", headers=user_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == str(counter - 1)
    page, _ = request(base, "/blog-comments/of/blog/%d?size=50" % blog_id, headers=user_headers)
    assert target not in [item["id"] for item in json.loads(page)["data"]["items"]]
    withdrawn = create_root(user_headers)
    request(base, "/blog-comments/" + withdrawn, 200, "DELETE", headers=user_headers)
    request(base, "/admin/blog-comments/" + withdrawn + "/hide", 404, "POST", payload, admin_headers)
    own_admin = create_root(admin_headers)
    max_reason = json.dumps({"reason": "x" * 255}).encode()
    request(base, "/admin/blog-comments/" + own_admin + "/hide", 200, "POST", max_reason, admin_headers)
    assert sql("SELECT CHAR_LENGTH(reason) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % own_admin) == "255"
    check("admin-only root hide: valid reason, session operator, immutable first audit, preserved descendants, repeat and withdrawal boundaries")

    for legacy in ("NULL", "0"):
        target = create_root(user_headers)
        sql("UPDATE tb_blog SET comments=%s WHERE id=%d;" % (legacy, blog_id))
        route = "/admin/blog-comments/" + target + "/hide"
        for _ in range(2):
            request(base, route, 200, "POST", payload, admin_headers)
        assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
        assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % target) == "1"
    target = create_root(user_headers)
    reply_route = "/blog-comments/" + target + "/replies"
    result, _ = request(base, reply_route, 200, "POST", b'{"content":"child survives moderation fault"}', user_headers)
    child = json.loads(result)["data"]["id"]
    route = "/admin/blog-comments/" + target + "/hide"
    for name, definition, expected, code in (
            ("synthetic_hide_status_failure", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private hide status failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_hide_counter_failure", "BEFORE UPDATE ON tb_blog FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private hide counter failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_hide_audit_failure", "BEFORE INSERT ON tb_blog_comment_hide_audit FOR EACH ROW "
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private hide audit failure'", 500, "INTERNAL_ERROR"),
            ("synthetic_hide_zero_counter", "BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
             "DELETE FROM tb_blog WHERE id=NEW.blog_id", 422, "OPERATION_FAILED")):
        sql("CREATE TRIGGER " + name + " " + definition + ";")
        try:
            result, _ = request(base, route, expected, "POST", payload, admin_headers)
            result = json.loads(result)
            assert result["success"] is False and result["errorCode"] == code
            assert "synthetic private" not in json.dumps(result)
            assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE id IN (%s,%s) AND status=0;" % (target, child)) == "2"
            assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "2"
            assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % target) == "0"
        finally:
            sql("DROP TRIGGER " + name + ";")
    request(base, route, 200, "POST", payload, admin_headers)
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "1"
    assert sql("SELECT status FROM tb_blog_comments WHERE id=%s;" % child) == "0"
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % target) == "1"
    check("root hide NULL/zero floor and status/counter/audit/zero-row faults atomically preserve original content, descendants and count")


def verify_comment_hide_audit_query(base, tokens):
    """Single-record metadata query, role isolation and retained-record semantics on target MySQL."""
    blog_id, root_id, operator_id, audit_id = (9007199254745001, 9007199254745003,
                                             9007199254745005, 9007199254745007)
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'00000000011','synthetic-audit-operator');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'ADMIN');"
        "INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
        "VALUES(%d,NULL,900001,'synthetic audit post','/synthetic.png','private post text',0,1);"
        "INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,liked,status) "
        "VALUES(%d,900001,%d,0,0,'private comment text',0,0);"
        "ALTER TABLE tb_blog_comment_hide_audit AUTO_INCREMENT=%d;" %
        (operator_id, operator_id, blog_id, root_id, blog_id, audit_id))
    headers_by_user = {}
    for user_id in (operator_id, 900007, 900008, 900009, 900010):
        token = "synthetic-audit-query-" + uuid.uuid4().hex
        redis("HSET", "login:token:" + token, "id", str(user_id), "nickName", "synthetic")
        redis("EXPIRE", "login:token:" + token, "600")
        headers_by_user[user_id] = {"authorization": token, "Content-Type": "application/json"}
    admin_headers = {"authorization": tokens[1], "Content-Type": "application/json"}
    user_headers = {"authorization": tokens[0]}
    hide_route = "/admin/blog-comments/%d/hide" % root_id
    route = "/admin/blog-comments/%d/hide-audit" % root_id
    request(base, hide_route, 200, "POST", b'{"reason":"stored synthetic audit reason"}',
            headers_by_user[operator_id])
    request(base, route, 401)
    # A comment author or merchant has no audit-query permission.
    for headers in (user_headers, headers_by_user[900007]):
        request(base, route, 403, headers=headers)
    for user_id in (900009, 900010):
        request(base, route, 401, headers=headers_by_user[user_id])
    # Even the original operator loses query access when its current role becomes USER.
    sql("UPDATE tb_user_role SET role='USER' WHERE user_id=%d;" % operator_id)
    request(base, route, 403, headers=headers_by_user[operator_id])
    sql("UPDATE tb_user_role SET role='ADMIN' WHERE user_id=%d;" % operator_id)
    for invalid in ("0", "-1", "bad", "9223372036854775808"):
        request(base, "/admin/blog-comments/" + invalid + "/hide-audit", 400, headers=admin_headers)

    def read_audit(headers):
        body, _ = request(base, route, headers=headers)
        result = json.loads(body)
        assert set(result) == {"success", "data"} and result["success"] is True
        data = result["data"]
        assert set(data) == {"id", "commentId", "blogId", "operatorUserId", "reason",
                             "fromStatus", "toStatus", "createTime"}
        for field, expected in (("id", audit_id), ("commentId", root_id), ("blogId", blog_id),
                                ("operatorUserId", operator_id)):
            assert isinstance(data[field], str) and data[field] == str(expected)
        assert data["reason"] == "stored synthetic audit reason"
        assert data["fromStatus"] == 0 and data["toStatus"] == 2
        stored_time = sql("SELECT DATE_FORMAT(create_time,'%Y-%m-%dT%H:%i:%s') "
                          "FROM tb_blog_comment_hide_audit WHERE comment_id=" + str(root_id) + ";")
        assert data["createTime"] == stored_time
        return data

    first = read_audit(admin_headers)
    request(base, hide_route, 200, "POST", b'{"reason":"different repeat reason"}', headers_by_user[900008])
    for headers in (admin_headers, headers_by_user[operator_id], headers_by_user[900008]):
        assert read_audit(headers) == first
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
    assert sql("SELECT CONCAT(status,':',content) FROM tb_blog_comments WHERE id=%d;" % root_id) == \
        "2:private comment text"
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%d;" % root_id) == "1"
    check("ADMIN-only audit query: exact metadata whitelist, four lossless large string IDs, current roles, original operator and repeated reads without mutations")

    sql("INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,status) "
        "VALUES(%d,900001,%d,0,0,'legacy hidden without audit',2),"
        "(%d,900001,%d,0,0,'author withdrawn without audit',3);" %
        (root_id + 10, blog_id, root_id + 11, blog_id))
    for missing in (root_id + 10, root_id + 11, 9223372036854775807):
        body, _ = request(base, "/admin/blog-comments/%d/hide-audit" % missing, 404, headers=admin_headers)
        assert json.loads(body)["errorCode"] == "NOT_FOUND"
    # Dedicated synthetic fixture rows only: retained audit is not a current-state or profile query.
    sql("DELETE FROM tb_blog_comments WHERE blog_id=%d; DELETE FROM tb_blog WHERE id=%d;"
        "DELETE FROM tb_user_role WHERE user_id=%d; DELETE FROM tb_user WHERE id=%d;" %
        (blog_id, blog_id, operator_id, operator_id))
    assert read_audit(admin_headers) == first
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=%d;" % root_id) == "1"
    check("audit query returns no inferred history for legacy/missing/withdrawn rows and retains stored metadata independently of removed comment, post and operator fixtures")


def verify_comment_hide_audit_pages(base, tokens):
    """Per-post ADMIN metadata pages, audit-ID cursor, bounded reads and retained-record semantics."""
    blog_id, other_blog, operator_id, root_start, audit_start = (
        9007199254750001, 9007199254750002, 9007199254750005, 9007199254750201, 9007199254750301)
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'00000000012','synthetic-audit-page-operator');"
        "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'ADMIN');"
        "INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
        "VALUES(%d,NULL,900001,'synthetic audit page','/synthetic.png','private page post',0,3),"
        "(%d,NULL,900001,'synthetic other audit post','/synthetic.png','private other post',0,1);"
        "INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,liked,status) "
        "VALUES(%d,900001,%d,0,0,'private page first root',0,0),"
        "(%d,900001,%d,0,0,'private page second root',0,0),"
        "(%d,900001,%d,0,0,'private page third root',0,0),"
        "(%d,900001,%d,0,0,'private page other root',0,0);"
        "ALTER TABLE tb_blog_comment_hide_audit AUTO_INCREMENT=%d;" %
        (operator_id, operator_id, blog_id, other_blog, root_start, blog_id, root_start + 1, blog_id,
         root_start + 2, blog_id, root_start + 3, other_blog, audit_start))
    headers_by_user = {}
    for user_id in (operator_id, 900007, 900008, 900009, 900010):
        token = "synthetic-audit-page-" + uuid.uuid4().hex
        redis("HSET", "login:token:" + token, "id", str(user_id), "nickName", "synthetic")
        redis("EXPIRE", "login:token:" + token, "600")
        headers_by_user[user_id] = {"authorization": token, "Content-Type": "application/json"}
    admin_headers = {"authorization": tokens[1]}
    route = "/admin/blog-comments/hide-audits/of/blog/%d" % blog_id
    for offset in (2, 3, 0, 1):
        request(base, "/admin/blog-comments/%d/hide" % (root_start + offset), 200, "POST",
                json.dumps({"reason": "synthetic page reason %d" % offset}).encode(), headers_by_user[operator_id])
    request(base, route, 401)
    for headers in ({"authorization": tokens[0]}, headers_by_user[900007]):
        request(base, route, 403, headers=headers)
    for user_id in (900009, 900010):
        request(base, route, 401, headers=headers_by_user[user_id])
    sql("UPDATE tb_user_role SET role='USER' WHERE user_id=%d;" % operator_id)
    request(base, route, 403, headers=headers_by_user[operator_id])
    sql("UPDATE tb_user_role SET role='ADMIN' WHERE user_id=%d;" % operator_id)
    for invalid in ("0", "-1", "bad", "9223372036854775808"):
        request(base, "/admin/blog-comments/hide-audits/of/blog/" + invalid, 400, headers=admin_headers)
    for query in ("size=0", "size=51", "size=bad", "beforeId=0", "beforeId=-1", "beforeId=bad",
                  "beforeId=9223372036854775808"):
        request(base, route + "?" + query, 400, headers=admin_headers)

    def read_page(query="", headers=None):
        body, _ = request(base, route + query, headers=headers or admin_headers)
        result = json.loads(body)
        assert set(result) == {"success", "data"} and result["success"] is True
        page = result["data"]
        assert set(page) == ({"items", "hasNext", "nextBeforeId"} if page["hasNext"] else {"items", "hasNext"})
        for item in page["items"]:
            assert set(item) == {"id", "commentId", "blogId", "operatorUserId", "reason", "fromStatus", "toStatus", "createTime"}
            for field in ("id", "commentId", "blogId", "operatorUserId"):
                assert isinstance(item[field], str)
            assert item["blogId"] == str(blog_id) and item["operatorUserId"] == str(operator_id)
            assert item["fromStatus"] == 0 and item["toStatus"] == 2
        return page

    first = read_page("?size=2&operatorUserId=900001&status=3&extra=true")
    assert [item["id"] for item in first["items"]] == [str(audit_start + 3), str(audit_start + 2)]
    assert [item["commentId"] for item in first["items"]] == [str(root_start + 1), str(root_start)]
    assert first["hasNext"] is True and first["nextBeforeId"] == str(audit_start + 2)
    last = read_page("?size=2&beforeId=" + first["nextBeforeId"])
    assert [item["id"] for item in last["items"]] == [str(audit_start)] and last["hasNext"] is False
    assert read_page("?size=3")["hasNext"] is False
    default = read_page()
    assert len(default["items"]) == 3 and default["hasNext"] is False
    assert read_page("?size=2", headers_by_user[operator_id]) == first
    assert read_page("?size=2", headers_by_user[900008]) == first
    assert sql("SELECT comments FROM tb_blog WHERE id=%d;" % blog_id) == "0"
    assert sql("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=%d AND status=2;" % blog_id) == "3"
    assert sql("SELECT content FROM tb_blog_comments WHERE id=%d;" % root_start) == "private page first root"
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE blog_id=%d;" % blog_id) == "3"
    check("ADMIN audit pages: per-post isolation, current-role checks, exact metadata whitelist, large string IDs, audit not comment cursor, defaults and strict input bounds")

    missing_route = "/admin/blog-comments/hide-audits/of/blog/9223372036854775807"
    empty, _ = request(base, missing_route, headers=admin_headers)
    assert json.loads(empty)["data"] == {"items": [], "hasNext": False}
    assert read_page("?beforeId=1") == {"items": [], "hasNext": False}
    # Retention is verified only against this dedicated synthetic fixture set.
    sql("DELETE FROM tb_blog_comments WHERE blog_id IN (%d,%d); DELETE FROM tb_blog WHERE id IN (%d,%d);"
        "DELETE FROM tb_user_role WHERE user_id=%d; DELETE FROM tb_user WHERE id=%d;" %
        (blog_id, other_blog, blog_id, other_blog, operator_id, operator_id))
    assert read_page() == default
    bulk_blog = blog_id + 100
    # Synthetic imported metadata exercises the 50-row bound; these are not claimed application hides.
    values = ",".join("(%d,%d,%d,%d,'synthetic bounded page',0,2)" %
                      (audit_start + 100 + index, root_start + 100 + index, bulk_blog, operator_id)
                      for index in range(51))
    sql("INSERT INTO tb_blog_comment_hide_audit(id,comment_id,blog_id,operator_user_id,reason,from_status,to_status) VALUES" + values + ";")
    bulk_route = "/admin/blog-comments/hide-audits/of/blog/%d" % bulk_blog
    body, _ = request(base, bulk_route, headers=admin_headers)
    default_bulk = json.loads(body)["data"]
    assert len(default_bulk["items"]) == 20 and default_bulk["hasNext"] is True
    assert default_bulk["nextBeforeId"] == str(audit_start + 131)
    body, _ = request(base, bulk_route + "?size=50", headers=admin_headers)
    bulk = json.loads(body)["data"]
    assert len(bulk["items"]) == 50 and bulk["hasNext"] is True
    assert bulk["nextBeforeId"] == str(audit_start + 101)
    body, _ = request(base, bulk_route + "?size=50&beforeId=" + bulk["nextBeforeId"], headers=admin_headers)
    assert [item["id"] for item in json.loads(body)["data"]["items"]] == [str(audit_start + 100)]
    assert json.loads(body)["data"]["hasNext"] is False
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE blog_id=%d;" % bulk_blog) == "51"
    sql((ROOT / "src/main/resources/db/migration/V009__add_blog_comment_hide_audit_page_index.sql").read_text("utf-8"))
    assert sql("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE blog_id=%d;" % bulk_blog) == "51"
    check("audit pages: empty/missing post 200, retained orphan metadata, default20 and maximum50 lookahead pages, immutable rows and V009 repeat preservation")


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
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comment_hide_audit' "
                   "AND INDEX_NAME='uk_blog_comment_hide_audit_comment' AND NON_UNIQUE=0;") == "comment_id"
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comment_hide_audit' "
                   "AND INDEX_NAME='idx_blog_comment_hide_audit_page';") == "blog_id,id"
        check("V001-V009 repeat, nullable post store, comment/hide audit cursor indexes, populated-database bootstrap refusal")
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900001,'00000000001','synthetic-user'),(900002,'00000000002','synthetic-admin');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(900001,'USER'),(900002,'ADMIN');")
        tokens = ["synthetic-phase5-" + uuid.uuid4().hex for _ in range(2)]
        for user, token in zip((900001, 900002), tokens):
            redis("HSET", "login:token:" + token, "id", str(user), "nickName", "synthetic")
            redis("EXPIRE", "login:token:" + token, "600")
        verify_campus_posts(base, tokens)
        verify_text_only_campus_posts(base, tokens)
        verify_first_level_comments(base, tokens)
        verify_direct_comment_replies(base, tokens)
        verify_reply_withdrawal(base, tokens)
        verify_first_level_withdrawal(base, tokens)
        verify_first_level_moderation(base, tokens)
        verify_comment_hide_audit_query(base, tokens)
        verify_comment_hide_audit_pages(base, tokens)
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
