#!/usr/bin/env python3
"""Own a fresh local Compose project, exercise real HTTP, then remove only its synthetic resources."""
import binascii
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import threading
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
           ORDER_CANCELLATION_RECONCILER_ENABLED="true", SHOP_CACHE_INVALIDATION_WORKER_ENABLED="true",
           BLOG_FEED_DELIVERY_WORKER_ENABLED="true")
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


def verify_login_code_module(base):
    """Only the owned test Redis receives codes; no SMS provider or personal account is used."""
    phones = ('13800992701', '13800992702', '13800992703')
    assert sql("SELECT COUNT(*) FROM tb_user WHERE phone IN (%s);" %
               ','.join("'%s'" % phone for phone in phones)) == '0'
    for phone in phones:
        assert redis('EXISTS', 'login:code:' + phone, 'login:code:cooldown:' + phone) == '0'

    def send(phone, expected=200, headers=None):
        body, _ = request(base, '/user/code?' + urllib.parse.urlencode({'phone': phone}),
                          expected, 'POST', headers=headers)
        return json.loads(body)

    expected_success = {'success': True, 'data': '发送验证码成功'}
    assert send(phones[0]) == expected_success
    code = redis('GET', 'login:code:' + phones[0])
    assert re.fullmatch(r'[0-9]{6}', code)
    code_ttl = int(redis('TTL', 'login:code:' + phones[0]))
    cooldown_ttl = int(redis('TTL', 'login:code:cooldown:' + phones[0]))
    assert 100 <= code_ttl <= 120 and 40 <= cooldown_ttl <= 60
    assert redis('GET', 'login:code:cooldown:' + phones[0]) == '1'
    assert send(phones[0], 429) == {'success': False, 'errorCode': 'RATE_LIMITED',
                                  'errorMsg': '验证码发送过于频繁，请稍后再试'}
    assert redis('GET', 'login:code:' + phones[0]) == code
    assert code_ttl - 30 <= int(redis('TTL', 'login:code:' + phones[0])) <= code_ttl
    assert cooldown_ttl - 30 <= int(redis('TTL', 'login:code:cooldown:' + phones[0])) <= cooldown_ttl
    # A missing/invalid authentication token does not close the existing public endpoint.
    assert send(phones[1], headers={'authorization': 'synthetic-missing-code-session'}) == expected_success
    second_code = redis('GET', 'login:code:' + phones[1])
    assert re.fullmatch(r'[0-9]{6}', second_code)
    for phone in ('', '   ', 'not-a-phone', ' ' + phones[0], phones[0] + ' '):
        assert send(phone, 400)['errorCode'] == 'VALIDATION_FAILED'
    body, _ = request(base, '/user/code', 400, 'POST')
    assert json.loads(body)['errorCode'] == 'VALIDATION_FAILED'
    assert sql("SELECT COUNT(*) FROM tb_user WHERE phone IN (%s);" %
               ','.join("'%s'" % phone for phone in phones)) == '0'
    check('identity login code: anonymous/invalid-session public access, exact response without code, six digits, unchanged 60s cooldown/120s code TTL, repeated 429 without refresh and independent phones')

    # Redis 6 ACL permits the cooldown SET/DEL but rejects the distinct code key's SET.
    redis('ACL', 'SETUSER', 'default', 'resetkeys', '~login:code:cooldown:*')
    try:
        failure = send(phones[2], 500)
        assert failure == {'success': False, 'errorCode': 'INTERNAL_ERROR', 'errorMsg': '服务器异常'}
        assert redis('EXISTS', 'login:code:cooldown:' + phones[2]) == '0'
    finally:
        redis('ACL', 'SETUSER', 'default', 'resetkeys', '~*')
    assert redis('EXISTS', 'login:code:' + phones[2], 'login:code:cooldown:' + phones[2]) == '0'
    assert redis('GET', 'login:code:' + phones[0]) == code
    assert redis('GET', 'login:code:' + phones[1]) == second_code
    assert send(phones[2]) == expected_success
    assert re.fullmatch(r'[0-9]{6}', redis('GET', 'login:code:' + phones[2]))
    check('identity login code save failure: real Redis key ACL rejects code SET after cooldown acquisition, cooldown DEL succeeds, ACL restored and retry stores a code without changing other phones')

    # Consume the generated test-only code through the unchanged login and logout chains.
    body, _ = request(base, '/user/login', 200, 'POST',
                      json.dumps({'phone': phones[0], 'code': code}).encode(), {'Content-Type': 'application/json'})
    result = json.loads(body)
    assert result['success'] is True and isinstance(result['data'], str) and result['data']
    token = result['data']
    assert redis('EXISTS', 'login:code:' + phones[0]) == '0'
    assert redis('EXISTS', 'login:code:cooldown:' + phones[0]) == '1'
    body, _ = request(base, '/user/me', headers={'authorization': token})
    actor = json.loads(body)['data']['id']
    assert sql("SELECT status FROM tb_user WHERE id=%d AND phone='%s';" % (actor, phones[0])) == 'ACTIVE'
    assert sql("SELECT role FROM tb_user_role WHERE user_id=%d;" % actor) == 'USER'
    body, _ = request(base, '/user/logout', 200, 'POST', headers={'authorization': token})
    assert json.loads(body) == {'success': True}
    request(base, '/user/me', 401, headers={'authorization': token})
    assert redis('GET', 'login:code:' + phones[1]) == second_code
    check('identity generated code compatibility: original login consumes the same Redis code, creates the synthetic USER/session, preserves cooldown and original logout revokes that session')


def verify_session_logout_module(base):
    """Revoke only this authenticated synthetic session, not every session of the account."""
    actors = ((991701, 'USER', 'ACTIVE'), (991702, 'MERCHANT', 'ACTIVE'), (991703, 'ADMIN', 'ACTIVE'),
              (991704, 'USER', 'DISABLED'), (991705, None, 'ACTIVE'))
    phones = ['%011d' % actor for actor, _, _ in actors]
    assert sql("SELECT COUNT(*) FROM tb_user WHERE phone IN (%s);" %
               ','.join("'%s'" % phone for phone in phones)) == '0'
    sessions = {}
    for actor, role, status in actors:
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'%011d','logout-actor','%s');" %
            (actor, actor, status))
        if role: sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (actor, role))
        sessions[actor] = []
        for _ in range(2):
            token = 'synthetic-logout-' + uuid.uuid4().hex
            redis('HSET', 'login:token:' + token, 'id', str(actor), 'nickName', 'session-logout')
            redis('EXPIRE', 'login:token:' + token, '600')
            sessions[actor].append(token)
    facts_sql = 'SELECT * FROM tb_user WHERE id BETWEEN 991701 AND 991705 ORDER BY id;'
    roles_sql = 'SELECT * FROM tb_user_role WHERE user_id BETWEEN 991701 AND 991705 ORDER BY user_id,role;'
    facts_before, roles_before = sql(facts_sql), sql(roles_sql)
    for actor, _, _ in actors[:3]:
        current, other = sessions[actor]
        other_map = redis('HGETALL', 'login:token:' + other)
        other_ttl = int(redis('TTL', 'login:token:' + other))
        body, _ = request(base, '/user/logout?userId=991705&token=' + other, 200, 'POST',
                          headers={'authorization': '  ' + current + '  '})
        assert json.loads(body) == {'success': True}
        assert redis('EXISTS', 'login:token:' + current) == '0'
        request(base, '/user/logout', 401, 'POST', headers={'authorization': current})
        request(base, '/user/me', 401, headers={'authorization': current})
        assert redis('HGETALL', 'login:token:' + other) == other_map
        assert other_ttl - 60 <= int(redis('TTL', 'login:token:' + other)) <= other_ttl
        body, _ = request(base, '/user/me', headers={'authorization': other})
        assert json.loads(body)['data']['id'] == actor
    for headers in ({}, {'authorization': '   '}, {'authorization': 'synthetic-expired-' + uuid.uuid4().hex}):
        request(base, '/user/logout', 401, 'POST', headers=headers)
    for actor, _, _ in actors[3:]:
        rejected, untouched = sessions[actor]
        request(base, '/user/logout', 401, 'POST', headers={'authorization': rejected})
        assert redis('EXISTS', 'login:token:' + rejected) == '0', 'retain existing authentication invalidation'
        assert redis('EXISTS', 'login:token:' + untouched) == '1', 'not an account-wide revoke'
    assert sql(facts_sql) == facts_before and sql(roles_sql) == roles_before
    check('identity logout: three roles, trimmed header, exact success body, current-session-only deletion, revoked replay 401, other-session preservation and existing authentication denial')

    # Reject DEL without breaking authentication's HGETALL/EXPIRE, then restore only this owned ACL.
    current = sessions[991701][1]
    key = 'login:token:' + current
    before = redis('HGETALL', key)
    redis('ACL', 'SETUSER', 'default', '-del')
    try:
        body, _ = request(base, '/user/logout', 500, 'POST', headers={'authorization': current})
        assert json.loads(body) == {'success': False, 'errorCode': 'INTERNAL_ERROR', 'errorMsg': '服务器异常'}
        assert redis('HGETALL', key) == before
        request(base, '/user/me', 401)  # No request identity leaks after the failed request.
    finally:
        redis('ACL', 'SETUSER', 'default', '+del')
    body, _ = request(base, '/user/me', headers={'authorization': current})
    assert json.loads(body)['data']['id'] == 991701
    body, _ = request(base, '/user/logout', 200, 'POST', headers={'authorization': current})
    assert json.loads(body) == {'success': True} and redis('EXISTS', key) == '0'
    request(base, '/user/me', 401, headers={'authorization': current})
    assert sql(facts_sql) == facts_before and sql(roles_sql) == roles_before
    check('identity logout failure: Redis DEL denied after authentication stays generic 500 with session retained, ACL restored, retry revokes and next request is unauthenticated')


def verify_blog_user_reader(base):
    """Own post/profile fixtures; preserve enrichment and the existing Redis top-five selection."""
    actor_ids = (992701, 992702, 992703)
    # Reserve phones independently of older scenarios' 7100/7200/7300/8100 suffix ranges.
    owned_phones = ['%011d' % number for number in (*actor_ids, *range(992801, 992806))]
    assert sql("SELECT COUNT(*) FROM tb_user WHERE phone IN (%s);" %
               ','.join("'%s'" % phone for phone in owned_phones)) == '0'
    headers = []
    for i, (actor, role) in enumerate(zip(actor_ids, ('USER', 'MERCHANT', 'ADMIN'))):
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'%s','actor','ACTIVE');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (actor, owned_phones[i], actor, role))
        token = 'synthetic-blog-reader-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(actor), 'nickName', 'session-not-author')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers.append({'authorization': token})
    author, first, second, missing, fourth, excluded = (9007199254745993, 9007199254745997,
                                                      9007199254745995, 9007199254745999,
                                                      9007199254746001, 9007199254746003)
    for index, user_id in enumerate((author, first, second, fourth, excluded)):
        sql("INSERT INTO tb_user(id,phone,password,nick_name,icon,status) VALUES"
            "(%d,'%s','synthetic-only','profile-%d','profile.svg','%s');" %
            (user_id, owned_phones[3 + index], user_id, 'DISABLED' if user_id in (author, second) else 'ACTIVE'))
    post, empty, orphan = 9007199254746101, 9007199254746103, 9007199254746105
    sql("INSERT INTO tb_blog(id,user_id,title,images,content,liked,comments,withdrawn) VALUES"
        "(%d,%d,'synthetic-reader','', 'body',2147483640,0,0),"
        "(%d,%d,'synthetic-reader-empty','', 'body',0,0,0);" % (post, author, empty, author))
    liked_key, feed_key = 'blog:liked:%d' % post, 'feed:%d' % actor_ids[0]
    # Reverse numeric order, a missing user among the first five, and a sixth that must not refill it.
    redis('ZADD', liked_key, '1', str(first), '2', str(second), '3', str(missing),
          '4', str(author), '5', str(fourth), '6', str(excluded))
    redis('ZADD', feed_key, '100', str(post), '99', str(empty))
    redis('EXPIRE', liked_key, '600'); redis('EXPIRE', feed_key, '600')
    users_sql = 'SELECT * FROM tb_user WHERE id IN (%s) ORDER BY id;' % ','.join(map(str, (author, first, second, fourth, excluded)))
    posts_sql = 'SELECT * FROM tb_blog WHERE id IN (%d,%d) ORDER BY id;' % (post, empty)
    users_before, posts_before = sql(users_sql), sql(posts_sql)
    projection_before = {key: redis('ZRANGE', key, '0', '-1', 'WITHSCORES') for key in (liked_key, feed_key)}

    def query(route, expected=200, auth=None):
        body, _ = request(base, route, expected, headers=auth or headers[0])
        return json.loads(body)

    def assert_likers(auth):
        result = query('/blog/likes/%d' % post, auth=auth)
        assert result['success'] is True
        assert [u['id'] for u in result['data']] == [first, second, author, fourth]
        for user in result['data']:
            assert set(user) == {'id', 'nickName', 'icon'} and type(user['id']) is int
            assert user['nickName'] == 'profile-%d' % user['id']

    for auth in headers:
        item = query('/blog/%d?userId=%d' % (post, actor_ids[1]), auth=auth)['data']
        assert item['id'] == post and item['userId'] == author and type(item['userId']) is int
        assert item['name'] == 'profile-%d' % author and item['icon'] == 'profile.svg' and item['isLike'] is False
        assert not ({'phone', 'password', 'status', 'nickName'} & set(item))
        assert_likers(auth)
    for route in ('/blog/%d' % post, '/blog/likes/%d' % post): request(base, route, 401)
    hot_body, _ = request(base, '/blog/hot')
    hot = json.loads(hot_body)['data'][0]
    assert hot['id'] == post and hot['name'] == 'profile-%d' % author and hot['isLike'] is False
    feed = query('/blog/of/follow?lastId=100&offset=0')['data']
    assert [item['id'] for item in feed['list']] == [post, empty]
    assert all(item['name'] == 'profile-%d' % author for item in feed['list'])
    assert feed['minTime'] == 99 and feed['offset'] == 1
    assert query('/blog/likes/%d' % empty)['data'] == []
    check('content user reader: database author vs session display, three roles, anonymous hot, Feed enrichment, ordered top-five without refill, disabled and missing users, numeric DTO whitelist')

    def failure(route):
        result = query(route, 500)
        assert result == {'success': False, 'errorMsg': '服务器异常', 'errorCode': 'INTERNAL_ERROR'}

    sql('ALTER TABLE tb_user RENAME COLUMN phone TO phase7z_owned_phone_fault;')
    try:
        failure('/blog/%d' % post)
        failure('/blog/likes/%d' % post)
        failure('/blog/of/follow?lastId=100&offset=0')
        assert query('/blog/likes/%d' % empty)['data'] == []
    finally:
        sql('ALTER TABLE tb_user RENAME COLUMN phase7z_owned_phone_fault TO phone;')
    for malformed in ('synthetic-not-a-long',):
        redis('ZADD', liked_key, '0', malformed)
        try: failure('/blog/likes/%d' % post)
        finally: redis('ZREM', liked_key, malformed)
    sql("INSERT INTO tb_blog(id,user_id,title,images,content,liked,comments,withdrawn) VALUES"
        "(%d,%d,'synthetic-orphan','', 'body',0,0,0);" % (orphan, missing))
    try:
        failure('/blog/%d' % orphan)  # Retain the old missing-author failure, not invented display data.
    finally:
        sql('DELETE FROM tb_blog WHERE id=%d;' % orphan)
    assert_likers(headers[0])
    assert query('/blog/%d' % post)['data']['name'] == 'profile-%d' % author
    assert sql(users_sql) == users_before and sql(posts_sql) == posts_before
    for key in projection_before:
        assert redis('ZRANGE', key, '0', '-1', 'WITHSCORES') == projection_before[key]
    check('content profile faults: SQL failures and malformed like members stay generic, empty likes skip user SELECT, missing author remains failure, recovery preserves facts and Redis projections')


def verify_user_profile_module(base):
    """Keep session identity separate from the two legacy, authenticated profile lookups."""
    target, orphan, missing = 9007199254744993, 9007199254744995, 9007199254744997
    headers = {}
    for index, (actor, role, state) in enumerate(((999101, 'USER', 'ACTIVE'), (999102, 'MERCHANT', 'ACTIVE'),
            (999103, 'ADMIN', 'ACTIVE'), (999104, 'USER', 'DISABLED'), (999105, None, 'ACTIVE'))):
        sql("INSERT INTO tb_user(id,phone,nick_name,icon,status) VALUES(%d,'000000%05d','sql-name','sql.svg','%s');" %
            (actor, 8100 + index, state))
        if role: sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (actor, role))
        token = 'synthetic-user-profile-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(actor), 'nickName', 'session-name', 'icon', 'session.svg')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[actor] = {'authorization': token}
    sql("INSERT INTO tb_user(id,phone,password,nick_name,icon,status) VALUES"
        "(%d,'00000008110','synthetic-private-user-password','target-name','target.svg','DISABLED');" % target)
    for user_id in (target, orphan):
        sql("INSERT INTO tb_user_info(user_id,city,introduce,fans,followee,gender,birthday,credits,level) VALUES"
            "(%d,'Xuancheng','Hello',3,2,0,'2001-02-03',10,1);" % user_id)
    user_rows = 'SELECT * FROM tb_user WHERE id IN (999101,999102,999103,%d) ORDER BY id;' % target
    info_rows = 'SELECT * FROM tb_user_info WHERE user_id IN (%d,%d) ORDER BY user_id;' % (target, orphan)
    before_users, before_infos = sql(user_rows), sql(info_rows)

    def query(actor, route, expected=200):
        body, _ = request(base, route, expected, headers=headers[actor])
        result = json.loads(body)
        if expected == 200: assert result['success'] is True
        return result

    for actor in (999101, 999102, 999103):
        assert query(actor, '/user/me?userId=%d' % target)['data'] == \
            {'id': actor, 'nickName': 'session-name', 'icon': 'session.svg'}
        item = query(actor, '/user/%d' % target)['data']
        assert item == {'id': target, 'nickName': 'target-name', 'icon': 'target.svg'} and type(item['id']) is int
        details = query(actor, '/user/info/%d' % target)['data']
        assert details == {'userId': target, 'city': 'Xuancheng', 'introduce': 'Hello', 'fans': 3, 'followee': 2,
                           'gender': False, 'birthday': '2001-02-03', 'credits': 10, 'level': True}
        assert type(details['userId']) is int and type(details['gender']) is bool and type(details['level']) is bool
    for prefix in ('/user/', '/user/info/'):
        assert query(999101, prefix + str(missing)) == {'success': True}
        for invalid in ('0', '-1', '9223372036854775808', 'bad'): query(999101, prefix + invalid, 400)
    assert query(999101, '/user/%d' % orphan) == {'success': True}
    assert query(999101, '/user/info/%d' % orphan)['data']['userId'] == orphan
    for route in ('/user/me', '/user/%d' % target, '/user/info/%d' % target):
        request(base, route, 401)
        request(base, route, 401, headers={'authorization': 'synthetic-expired-profile-' + uuid.uuid4().hex})
        for actor in (999104, 999105): assert query(actor, route, 401)['errorCode'] == 'AUTHENTICATION_FAILED'
    assert sql(user_rows) == before_users and sql(info_rows) == before_infos
    check('identity profile: three roles, session-only me vs SQL target, numeric IDs and exact DTO/LocalDate/boolean fields, disabled target/orphan compatibility, missing success, input and authentication boundaries')

    # Auth reads only id/status plus roles; breaking the profile-only phone column leaves auth functional.
    sql('ALTER TABLE tb_user RENAME COLUMN phone TO phase7x_owned_phone_fault;')
    try:
        failure = query(999101, '/user/%d' % target, 500)
        assert failure['errorCode'] == 'INTERNAL_ERROR' and 'phone' not in failure.get('errorMsg', '')
        assert query(999101, '/user/info/%d' % target)['data']['userId'] == target
        sql('RENAME TABLE tb_user_info TO phase7x_owned_info_fault;')
        try:
            assert query(999101, '/user/info/%d' % target, 500)['errorCode'] == 'INTERNAL_ERROR'
            assert query(999101, '/user/me')['data']['nickName'] == 'session-name'
        finally:
            sql('RENAME TABLE phase7x_owned_info_fault TO tb_user_info;')
    finally:
        sql('ALTER TABLE tb_user RENAME COLUMN phase7x_owned_phone_fault TO phone;')
    assert sql(user_rows) == before_users and sql(info_rows) == before_infos
    assert query(999101, '/user/%d' % target)['data']['id'] == target
    assert query(999101, '/user/info/%d' % target)['data']['userId'] == target
    check('identity profile: independent SQL fault propagation and restored schemas, session me needs neither profile query, no writes or hidden join added')


def verify_voucher_query_module(base):
    """Preserve the legacy XML catalog over synthetic rows, including its LEFT JOIN boundaries."""
    shop, ordinary = 9007199254743993, 9007199254744001
    sql("INSERT INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES"
        "(%d,%d,'synthetic-ordinary','subtitle','rules',9007199254741993,9007199254742993,0,1),"
        "(998302,%d,'synthetic-expired','s','r',1,2,1,1),"
        "(998303,%d,'synthetic-future','s','r',1,2,1,1),"
        "(998304,%d,'synthetic-no-stock-row','s','r',1,2,1,1),"
        "(998305,%d,'synthetic-hidden','s','r',1,2,1,0),"
        "(998306,%d,'synthetic-other-shop','s','r',1,2,0,1);" %
        (ordinary, shop, shop, shop, shop, shop, shop + 1))
    sql("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES"
        "(998302,0,'2000-01-01 01:02:03','2000-01-02 02:03:04'),"
        "(998303,7,'2035-01-01 03:04:05','2035-01-02 04:05:06');")
    route = '/voucher/list/%d' % shop
    expected = {ordinary, 998302, 998303, 998304}
    snapshot_sql = 'SELECT * FROM tb_voucher WHERE shop_id IN (%d,%d) ORDER BY id;' % (shop, shop + 1)
    stock_sql = 'SELECT * FROM tb_seckill_voucher WHERE voucher_id IN (998302,998303) ORDER BY voucher_id;'
    before, stock_before = sql(snapshot_sql), sql(stock_sql)
    body, _ = request(base, route)
    result = json.loads(body)
    assert result['success'] is True and {v['id'] for v in result['data']} == expected
    items = {v['id']: v for v in result['data']}
    base_fields = {'id', 'shopId', 'title', 'subTitle', 'rules', 'payValue', 'actualValue', 'type'}
    assert set(items[ordinary]) == base_fields and set(items[998304]) == base_fields
    assert type(items[ordinary]['id']) is int and items[ordinary]['shopId'] == shop
    assert items[ordinary]['payValue'] == 9007199254741993 and items[ordinary]['actualValue'] == 9007199254742993
    assert items[998302]['stock'] == 0 and items[998302]['beginTime'] == '2000-01-01T01:02:03'
    assert items[998303]['endTime'] == '2035-01-02T04:05:06'
    for row in items.values(): assert not ({'status', 'createTime', 'updateTime'} & set(row))
    body, _ = request(base, '/voucher/list/%d' % (shop + 2)); assert json.loads(body)['data'] == []
    for invalid in ('0', '-1', '9223372036854775808', 'bad'):
        request(base, '/voucher/list/' + invalid, 400)
    assert sql(snapshot_sql) == before and sql(stock_sql) == stock_before
    sql('UPDATE tb_voucher SET status=0 WHERE id=998304;')
    hidden_snapshot = sql(snapshot_sql)
    body, _ = request(base, route); assert {v['id'] for v in json.loads(body)['data']} == expected - {998304}
    assert sql(snapshot_sql) == hidden_snapshot and sql(stock_sql) == stock_before
    sql('UPDATE tb_voucher SET status=1 WHERE id=998304;')
    # Fixture UPDATE intentionally advances ON UPDATE timestamps; subsequent reads must preserve this baseline.
    before = sql(snapshot_sql)
    assert sql(stock_sql) == stock_before
    check('promotion catalog: anonymous public read, numeric large IDs/amounts, status-only filtering, LEFT JOIN ordinary/missing/expired/zero/future stock, no shop-existence rule or cache')

    for table, fault in (('tb_voucher', 'phase7w_owned_voucher_fault'),
                         ('tb_seckill_voucher', 'phase7w_owned_stock_fault')):
        sql('RENAME TABLE %s TO %s;' % (table, fault))
        try:
            body, _ = request(base, route, 500)
            result = json.loads(body)
            assert result['success'] is False and result['errorCode'] == 'INTERNAL_ERROR'
            assert table not in result.get('errorMsg', '') and 'SQL' not in result.get('errorMsg', '')
        finally:
            sql('RENAME TABLE %s TO %s;' % (fault, table))
    assert sql(snapshot_sql) == before and sql(stock_sql) == stock_before
    body, _ = request(base, route); assert {v['id'] for v in json.loads(body)['data']} == expected
    check('promotion catalog: either SQL table fault stays generic 500, no empty-success fallback or data writes, recovery reads the same rows')


def verify_follower_reader_publication(base):
    """Private author/follower facts prove the publication adapter does not read Redis or join users."""
    author, active, disabled, missing, other = 997301, 997302, 997303, 997304, 997305
    for index, (actor, status) in enumerate(((author, 'ACTIVE'), (active, 'ACTIVE'), (disabled, 'DISABLED'), (other, 'ACTIVE'))):
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'000000%05d','synthetic-follower-port','%s');" %
            (actor, 7300 + index, status))
    sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'USER');" % author)
    sql('INSERT INTO tb_follow(user_id,follow_user_id) VALUES(%d,%d),(%d,%d),(%d,%d),(%d,%d);' %
        (active, author, disabled, author, missing, author, other, active))
    token = 'synthetic-follower-port-' + uuid.uuid4().hex
    redis('HSET', 'login:token:' + token, 'id', str(author), 'nickName', 'synthetic')
    redis('EXPIRE', 'login:token:' + token, '600')
    headers = {'authorization': token, 'Content-Type': 'application/json'}
    follows_key = 'follows:%d' % author
    redis('SET', follows_key, 'synthetic-unread-projection')
    for recipient in (active, disabled, missing):
        redis('SET', 'feed:%d' % recipient, 'synthetic-hold-intent')
    relation_sql = 'SELECT user_id,follow_user_id FROM tb_follow WHERE follow_user_id=%d ORDER BY user_id;' % author
    relations_before = sql(relation_sql)

    def publish(title, expected=200):
        body, _ = request(base, '/blog', expected, 'POST', json.dumps(
            {'title': title, 'content': 'synthetic follower reader body'}).encode(), headers)
        result = json.loads(body)
        if expected == 200:
            assert result['success'] is True
            return int(result['data'])
        assert result['success'] is False and result['errorCode'] == 'INTERNAL_ERROR'
        assert 'tb_follow' not in result.get('errorMsg', '') and 'SQL' not in result.get('errorMsg', '')

    try:
        first = publish('synthetic follower port audience')
        assert sql('SELECT GROUP_CONCAT(recipient_user_id ORDER BY recipient_user_id) FROM tb_blog_feed_delivery WHERE blog_id=%d;' % first) == \
            ','.join(map(str, (active, disabled, missing)))
        assert sql('SELECT COUNT(DISTINCT score_millis) FROM tb_blog_feed_delivery WHERE blog_id=%d;' % first) == '1'
        assert redis('GET', follows_key) == 'synthetic-unread-projection'
        assert sql(relation_sql) == relations_before
        check('follower port publication: SQL audience only, author isolation, retained missing/disabled followers, unread Redis follows and original per-post score')

        sql('RENAME TABLE tb_follow TO phase7v_owned_follower_fault;')
        try:
            publish('synthetic follower port rollback', 500)
        finally:
            sql('RENAME TABLE phase7v_owned_follower_fault TO tb_follow;')
        assert sql("SELECT COUNT(*) FROM tb_blog WHERE user_id=%d AND title='synthetic follower port rollback';" % author) == '0'
        assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id IN (%d,%d,%d);' %
                   (active, disabled, missing)) == '3'
        assert sql(relation_sql) == relations_before
        second = publish('synthetic follower port recovered')
        assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE blog_id=%d;' % second) == '3'
        check('follower port SQL failure: generic HTTP 500 rolls back the new post without orphan intent; restored adapter publishes again')
    finally:
        redis('DEL', follows_key, *('feed:%d' % recipient for recipient in (active, disabled, missing)))


def verify_follow_command_module(base):
    """Exercise only this project's synthetic follow facts and projections; no new consistency promise."""
    headers = {}
    for index, (actor, role, status) in enumerate(((997201, 'USER', 'ACTIVE'), (997202, 'MERCHANT', 'ACTIVE'),
            (997203, 'ADMIN', 'ACTIVE'), (997204, 'USER', 'DISABLED'), (997205, None, 'ACTIVE'))):
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'000000%05d','synthetic-follow-command','%s');" %
            (actor, 7200 + index, status))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (actor, role))
        token = 'synthetic-follow-command-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(actor), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[actor] = {'authorization': token}
    target = 9007199254742993  # Intentionally no target user row: do not add an existence rule.

    def write(actor, follow, expected=200, target_id=target):
        body, _ = request(base, '/follow/%s/%s?userId=997205' % (target_id, follow), expected, 'PUT', headers=headers[actor])
        result = json.loads(body)
        if expected == 200:
            assert result == {'success': True}
        else:
            assert result['success'] is False
            assert result['errorCode'] == {400: 'VALIDATION_FAILED', 401: 'AUTHENTICATION_FAILED',
                                            409: 'CONFLICT', 500: 'INTERNAL_ERROR'}[expected]
        return result

    def count(actor):
        return sql('SELECT COUNT(*) FROM tb_follow WHERE user_id=%d AND follow_user_id=%d;' % (actor, target))

    request(base, '/follow/%d/true' % target, 401, 'PUT')
    for actor in (997204, 997205): write(actor, 'true', 401)
    for invalid in ('0', '-1', '9223372036854775808', 'bad'):
        write(997201, 'true', 400, invalid)
    write(997201, 'not-boolean', 400)
    for actor in (997201, 997202, 997203):
        for flag in ('true', 'false'): write(actor, flag, 409, actor)
        key = 'follows:%d' % actor
        write(actor, 'true')
        assert count(actor) == '1' and float(redis('ZSCORE', key, str(target))) > 1
        redis('ZADD', key, '1', str(target))
        redis('EXPIRE', key, '600')
        before_ttl = int(redis('TTL', key))
        write(actor, 'true')
        assert count(actor) == '1' and float(redis('ZSCORE', key, str(target))) > 1
        assert before_ttl - 30 <= int(redis('TTL', key)) <= before_ttl
        write(actor, 'false')
        assert count(actor) == '0' and redis('ZSCORE', key, str(target)) == ''
        redis('ZADD', key, '7', str(target))  # Absent fact + stale projection stays stale on repeated unfollow.
        write(actor, 'false')
        assert count(actor) == '0' and redis('ZSCORE', key, str(target)) == '7'
        redis('DEL', key)
    assert count(997205) == '0', 'query actor must not replace authenticated actor'
    check('social follow writes: three roles, session actor, positive Long/Boolean validation, self conflict, duplicate refresh/TTL and unchanged repeated-unfollow semantics')

    key = 'follows:997201'
    redis('SET', key, 'synthetic-wrong-type')
    result = write(997201, 'true', 500)
    assert count(997201) == '1' and redis('GET', key) == 'synthetic-wrong-type'
    assert 'WRONGTYPE' not in result.get('errorMsg', '') and 'follows:' not in result.get('errorMsg', '')
    redis('DEL', key)
    write(997201, 'true')  # Repeated follow can refresh the projection after its earlier failure.
    assert count(997201) == '1' and float(redis('ZSCORE', key, str(target))) > 1
    redis('DEL', key)
    redis('SET', key, 'synthetic-delete-fault')
    write(997201, 'false', 500)
    assert count(997201) == '0' and redis('GET', key) == 'synthetic-delete-fault'
    write(997201, 'false')  # No deleted row, hence no Redis operation or automatic repair.
    assert redis('GET', key) == 'synthetic-delete-fault'
    redis('DEL', key)
    redis('ZADD', key, '9', str(target))
    sql('RENAME TABLE tb_follow TO phase7u_owned_follow_fault;')
    try:
        for flag in ('true', 'false'): write(997201, flag, 500)
        assert redis('ZSCORE', key, str(target)) == '9'
    finally:
        sql('RENAME TABLE phase7u_owned_follow_fault TO tb_follow;')
    assert count(997201) == '0'
    write(997201, 'true')
    write(997201, 'false')
    assert count(997201) == '0' and redis('ZSCORE', key, str(target)) == ''
    check('social follow writes: real Redis failures retain committed SQL, retry boundaries, SQL faults leave projection unchanged and recovery is usable')


def verify_follow_query_module(base):
    """Read-only social slice over its own actors, relationships and legacy ZSet projection."""
    roles = ((997101, 'USER', 'ACTIVE'), (997102, 'MERCHANT', 'ACTIVE'), (997103, 'ADMIN', 'ACTIVE'),
             (997104, 'USER', 'DISABLED'), (997105, None, 'ACTIVE'), (997110, 'USER', 'ACTIVE'))
    headers = {}
    for index, (user_id, role, account_status) in enumerate(roles):
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'000000%05d','synthetic-follow-reader','%s');" %
            (user_id, 7100 + index, account_status))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-follow-query-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token}
    shared, disabled_profile, missing = 9007199254741993, 9007199254741995, 9007199254741997
    sql("INSERT INTO tb_user(id,phone,password,nick_name,icon,status) VALUES"
        "(%d,'00000007110','synthetic-only','shared','shared.svg','ACTIVE'),"
        "(%d,'00000007111','synthetic-only','retained-profile','retained.svg','DISABLED');" % (shared, disabled_profile))
    for actor in (997101, 997102, 997103, 997110):
        body, _ = request(base, '/follow/%d/true' % shared, 200, 'PUT', headers=headers[actor])
        assert json.loads(body) == {'success': True}

    def query(actor, route, expected=200):
        body, _ = request(base, route, expected, headers=headers[actor])
        return json.loads(body)

    def assert_profiles(result, expected):
        assert result['success'] is True
        items = result['data']
        assert {item['id'] for item in items} == set(expected)
        for item in items:
            assert set(item) == {'id', 'nickName', 'icon'} and type(item['id']) is int

    for actor in (997101, 997102, 997103):
        assert query(actor, '/follow/or/not/%d?userId=997105' % shared)['data'] is True
        assert_profiles(query(actor, '/follow/common/997110?userId=997105'), {shared})
    for route in ('/follow/or/not/%d' % shared, '/follow/common/997110'):
        request(base, route, 401)
        for actor in (997104, 997105):
            assert query(actor, route, 401)['errorCode'] == 'AUTHENTICATION_FAILED'
    for invalid in ('0', '-1', '9223372036854775808', 'not-a-number'):
        for prefix in ('/follow/or/not/', '/follow/common/'):
            assert query(997101, prefix + invalid, 400)['errorCode'] == 'VALIDATION_FAILED'
    check('social follow queries: current roles and identity, positive Long validation, old write endpoint and numeric-ID UserDTO whitelist')

    actor_key, target_key = 'follows:997101', 'follows:997110'
    for key in (actor_key, target_key):
        redis('ZADD', key, '12', str(disabled_profile), '13', str(missing))
        redis('EXPIRE', key, '600')
    before = {key: redis('ZRANGE', key, '0', '-1', 'WITHSCORES') for key in (actor_key, target_key)}
    ttl = {key: int(redis('TTL', key)) for key in (actor_key, target_key)}
    rows_sql = 'SELECT user_id,follow_user_id FROM tb_follow WHERE user_id IN (997101,997102,997103,997110) ORDER BY user_id,follow_user_id;'
    rows_before = sql(rows_sql)
    assert query(997101, '/follow/or/not/%d' % disabled_profile)['data'] is False
    assert_profiles(query(997101, '/follow/common/997110'), {shared, disabled_profile})
    assert_profiles(query(997101, '/follow/common/997101'), {shared, disabled_profile})
    assert query(997101, '/follow/or/not/997101')['data'] is False
    for key in before:
        assert redis('ZRANGE', key, '0', '-1', 'WITHSCORES') == before[key]
        assert ttl[key] - 30 <= int(redis('TTL', key)) <= ttl[key], 'query must not refresh or remove TTL'

    def generic_failure(route, actor=997101):
        result = query(actor, route, 500)
        assert result['success'] is False and result['errorCode'] == 'INTERNAL_ERROR'
        assert 'SQL' not in result.get('errorMsg', '') and 'follows:' not in result.get('errorMsg', '')

    for key in (actor_key, target_key): redis('ZADD', key, '14', 'synthetic-not-a-long')
    malformed = {key: redis('ZRANGE', key, '0', '-1', 'WITHSCORES') for key in before}
    generic_failure('/follow/common/997110')
    for key in before:
        assert redis('ZRANGE', key, '0', '-1', 'WITHSCORES') == malformed[key]
        redis('ZREM', key, 'synthetic-not-a-long')

    admin_key = 'follows:997103'
    redis('ZREM', admin_key, str(shared))
    assert query(997103, '/follow/or/not/%d' % shared)['data'] is True
    assert_profiles(query(997103, '/follow/common/997110'), set())
    redis('SET', admin_key, 'synthetic-wrong-type')
    try:
        generic_failure('/follow/common/997110', 997103)
        assert redis('GET', admin_key) == 'synthetic-wrong-type'
        assert query(997103, '/follow/or/not/%d' % shared)['data'] is True
    finally:
        redis('DEL', admin_key)

    sql('RENAME TABLE tb_follow TO phase7t_owned_follow_fault;')
    try:
        generic_failure('/follow/or/not/%d' % shared)
        assert_profiles(query(997101, '/follow/common/997110'), {shared, disabled_profile})
    finally:
        sql('RENAME TABLE phase7t_owned_follow_fault TO tb_follow;')
    assert sql(rows_sql) == rows_before
    for key in before: assert redis('ZRANGE', key, '0', '-1', 'WITHSCORES') == before[key]
    assert query(997101, '/follow/or/not/%d' % shared)['data'] is True
    request(base, '/follow/%d/true' % shared, 200, 'PUT', headers=headers[997101])
    assert sql(rows_sql) == rows_before
    check('social follow queries: SQL truth vs Redis intersection, missing/disabled profiles, self reads, unchanged projection and sanitized faults/recovery without repair')

    # Keep authentication's id/status/roles intact while the batch profile SELECT fails.
    # The preceding repeated-follow request intentionally refreshed the shared member's score.
    profile_projection_before = {key: redis('ZRANGE', key, '0', '-1', 'WITHSCORES') for key in before}
    profiles_sql = ('SELECT id,phone,password,nick_name,icon,status,create_time,update_time '
                    'FROM tb_user WHERE id IN (%d,%d) ORDER BY id;' % (shared, disabled_profile))
    profiles_before = sql(profiles_sql)
    sql('ALTER TABLE tb_user RENAME COLUMN phone TO phase7y_owned_phone_fault;')
    try:
        generic_failure('/follow/common/997110')
        assert query(997101, '/follow/or/not/%d' % shared)['data'] is True
        assert_profiles(query(997102, '/follow/common/997103'), set())
    finally:
        sql('ALTER TABLE tb_user RENAME COLUMN phase7y_owned_phone_fault TO phone;')
    assert_profiles(query(997101, '/follow/common/997110'), {shared, disabled_profile})
    assert sql(profiles_sql) == profiles_before
    assert sql(rows_sql) == rows_before
    for key in profile_projection_before:
        assert redis('ZRANGE', key, '0', '-1', 'WITHSCORES') == profile_projection_before[key]
    check('social batch profiles: SQL failure propagates, empty intersection skips profile SELECT, recovery preserves rows and projections')


def verify_shop_type_module(base, tokens):
    """Only this fresh project's categories/cache are manipulated; retain the legacy public read contract."""
    key = 'cache:shop:type'
    fixture_ids = {995201, 995202, 995203, 995204}
    sql("INSERT INTO tb_shop_type(id,name,icon,sort) VALUES"
        "(995201,'synthetic-type-late','late.svg',9),(995202,'synthetic-type-early','early.svg',2),"
        "(995203,'synthetic-type-null','null.svg',NULL),(995204,'synthetic-type-tie','tie.svg',2);")
    redis('DEL', key)
    body, _ = request(base, '/shop-type/list')
    payload = json.loads(body)
    assert payload['success'] is True
    items = payload['data']
    own = [row for row in items if row['id'] in fixture_ids]
    assert len(own) == 4 and own[0]['id'] == 995203 and own[-1]['id'] == 995201
    assert {row['id'] for row in own[1:3]} == {995202, 995204}, 'equal sort values have no promised tie order'
    for row in own:
        assert type(row['id']) is int
        expected_fields = {'id', 'name', 'icon'} | ({'sort'} if row['id'] != 995203 else set())
        assert set(row) == expected_fields, 'preserve entity JSON and ignored timestamps'
    raw = redis('GET', key)
    assert len(json.loads(raw)) == len(items) and 1790 <= int(redis('TTL', key)) <= 1800
    for token in tokens:  # The initial synthetic USER and ADMIN; this endpoint has no participation guard.
        request(base, '/shop-type/list', headers={'authorization': token})
    for user_id, role, status in ((995210, 'MERCHANT', 'ACTIVE'), (995211, 'USER', 'DISABLED'), (995212, None, 'ACTIVE')):
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'%011d','synthetic-type-reader','%s');" %
            (user_id, user_id, status))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-type-reader-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        request(base, '/shop-type/list', headers={'authorization': token})
    request(base, '/shop-type/list', headers={'authorization': 'synthetic-expired-' + uuid.uuid4().hex})
    check('merchant categories: actual public roles, sort/NULL/tie contract, numeric IDs, hidden timestamps and legacy cache key/TTL')

    def generic_failure():
        body, _ = request(base, '/shop-type/list', 500)
        result = json.loads(body)
        assert result['success'] is False and result['errorCode'] == 'INTERNAL_ERROR'
        assert 'shop_type' not in result.get('errorMsg', '') and 'SQL' not in result.get('errorMsg', '')

    sql('RENAME TABLE tb_shop_type TO phase7s_owned_shop_type_fault;')
    try:
        redis('EXPIRE', key, '120')
        body, _ = request(base, '/shop-type/list')
        assert json.loads(body)['data'] == items and redis('GET', key) == raw
        assert 110 <= int(redis('TTL', key)) <= 120, 'cache hit must not reset TTL'
        redis('SET', key, '[]')
        body, _ = request(base, '/shop-type/list')
        assert json.loads(body)['data'] == [] and redis('GET', key) == '[]'
        redis('SET', key, '{broken')
        generic_failure()
        assert redis('GET', key) == '{broken'
        redis('SET', key, ' ')
        generic_failure()
        # The shared command helper strips text whitespace; compare the exact stored byte instead.
        assert redis('STRLEN', key) == '1' and redis('BITFIELD', key, 'GET', 'u8', '0') == '32', \
            'SQL failure must preserve the single ASCII-space cache value'
    finally:
        sql('RENAME TABLE phase7s_owned_shop_type_fault TO tb_shop_type;')
        redis('DEL', key)
    redis('SET', key, ' ')
    body, _ = request(base, '/shop-type/list')
    restored = json.loads(body)['data']
    assert {row['id'] for row in restored if row['id'] in fixture_ids} == fixture_ids
    assert 1790 <= int(redis('TTL', key)) <= 1800
    sql('DELETE FROM tb_shop_type WHERE id IN (995201,995202,995203,995204);')
    redis('DEL', key)
    check('merchant categories: cache hit and empty hit skip unavailable SQL; malformed JSON/SQL faults stay generic; blank cache refills after recovery')


def verify_check_in_module(base):
    """Exercise the moved controller and real monthly bitmap without changing existing business rules."""
    actors = ((995101, ('USER',)), (995102, ('MERCHANT',)), (995103, ('ADMIN', 'USER')),
              (995104, ('USER',)), (995105, ()))
    headers = {}
    for user_id, roles in actors:
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'%011d','synthetic-check-in');" % (user_id, user_id))
        for role in roles:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-check-in-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token}
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=995104;")
    stamp = compose('exec', '-T', 'app', 'date', '+%Y%m%d')
    assert re.fullmatch(r'[0-9]{8}', stamp)
    month, day = stamp[:6], int(stamp[6:])
    user_key, merchant_key = 'sign:995101:' + month, 'sign:995102:' + month
    assert redis('EXISTS', user_key, merchant_key) == '0'
    for path, method in (('/user/sign', 'POST'), ('/user/sign/count', 'GET')):
        body, _ = request(base, path, 401, method)
        assert json.loads(body)['errorCode'] == 'AUTHENTICATION_FAILED'
        for user_id, expected in ((995103, 403), (995104, 401), (995105, 401)):
            request(base, path, expected, method, headers=headers[user_id])
    body, _ = request(base, '/user/sign/count', headers=headers[995101])
    assert json.loads(body)['data'] == 0
    for _ in range(2):
        body, _ = request(base, '/user/sign?userId=995102', 200, 'POST', headers=headers[995101])
        assert json.loads(body) == {'success': True}
    body, _ = request(base, '/user/sign/count', headers=headers[995101])
    assert json.loads(body)['data'] == 1
    assert redis('BITCOUNT', user_key) == '1'
    assert redis('GETBIT', user_key, str(day - 1)) == '1'
    assert redis('TTL', user_key) == '-1'
    assert redis('EXISTS', merchant_key) == '0'
    assert redis('EXISTS', *('sign:%d:%s' % (actor, month) for actor in (995103, 995104, 995105))) == '0'
    check('engagement check-in routes preserve identity, role denial, one-bit repeat and no expiry over real HTTP/Redis')
    # Import the exact legacy key/offset format before invoking the new application service.
    for offset in range(day):
        redis('SETBIT', merchant_key, str(offset), '1')
    historical = 'sign:995102:199901'
    redis('SETBIT', historical, '30', '1')
    body, _ = request(base, '/user/sign/count', headers=headers[995102])
    assert json.loads(body)['data'] == day
    request(base, '/user/sign', 200, 'POST', headers=headers[995102])
    assert redis('BITCOUNT', merchant_key) == str(day)
    assert redis('TTL', merchant_key) == '-1'
    assert redis('GETBIT', historical, '30') == '1'
    sql("INSERT INTO tb_user_role(user_id,role) VALUES(995102,'ADMIN');")
    request(base, '/user/sign', 403, 'POST', headers=headers[995102])
    request(base, '/user/sign/count', 403, headers=headers[995102])
    assert redis('BITCOUNT', merchant_key) == str(day)
    assert compose('exec', '-T', 'app', 'date', '+%Y%m%d') == stamp, 'rerun check-in fixture if it crosses midnight'
    check('engagement reads preexisting monthly bitmaps and current roles without changing historical buckets')


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


def verify_direct_reply_moderation(base):
    """Own synthetic actors/posts; verify reply-only ADMIN writes and retained root-only read APIs."""
    actors = ((900110, 'USER'), (900111, 'MERCHANT'), (900112, 'ADMIN'),
              (900113, 'ADMIN'), (900114, 'ADMIN'), (900115, None))
    headers = {}
    for user_id, role in actors:
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'%011d','synthetic-reply-moderation');" %
            (user_id, user_id))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-reply-hide-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900114;")
    author, admin = headers[900110], headers[900112]
    payload = b'{"reason":"retained first reply reason","operatorUserId":900110,"status":3,"blogId":99,"rootCommentId":1}'

    def post():
        body, _ = request(base, '/blog', 200, 'POST',
                          b'{"title":"synthetic reply moderation","content":"retained post"}', author)
        return int(json.loads(body)['data'])

    def root(blog):
        body, _ = request(base, '/blog-comments', 200, 'POST',
                          json.dumps({'blogId': str(blog), 'content': 'retained root'}).encode(), author)
        return json.loads(body)['data']['id']

    def reply(parent, actor=author):
        body, _ = request(base, '/blog-comments/' + parent + '/replies', 200, 'POST',
                          b'{"content":"retained reply"}', actor)
        return json.loads(body)['data']['id']

    def route(target):
        return '/admin/blog-comments/replies/' + str(target) + '/hide'

    def count(blog):
        return sql('SELECT comments FROM tb_blog WHERE id=%d;' % blog)

    def retained(target):
        return sql("SELECT CONCAT_WS('|',id,user_id,blog_id,parent_id,answer_id,content,liked,create_time) "
                   "FROM tb_blog_comments WHERE id=%s;" % target)

    blog = post()
    parent = root(blog)
    target, sibling = reply(parent), reply(parent, headers[900111])
    assert int(target) > 9007199254740992, 'retain existing large-ID acceptance boundary'
    before = {row: retained(row) for row in (parent, target, sibling)}
    counter = int(count(blog))
    request(base, route(target), 401, 'POST', payload, {'Content-Type': 'application/json'})
    for user_id in (900110, 900111):
        request(base, route(target), 403, 'POST', payload, headers[user_id])
    for user_id in (900114, 900115):
        request(base, route(target), 401, 'POST', payload, headers[user_id])
    for invalid in ('0', '-1', 'bad', '9223372036854775808'):
        request(base, route(invalid), 400, 'POST', payload, admin)
    for body in (b'{', b'null', b'{}', b'{"reason":null}', b'{"reason":" "}',
                 json.dumps({'reason': 'x' * 256}).encode(),
                 json.dumps({'reason': '\U0001f600' * 128}).encode()):
        request(base, route(target), 400, 'POST', body, admin)
    assert count(blog) == str(counter)
    for actor, body in ((admin, payload), (headers[900113], b'{"reason":"later admin reason"}')):
        response, _ = request(base, route(target), 200, 'POST', body, actor)
        assert json.loads(response) == {'success': True}
    assert count(blog) == str(counter - 1)
    assert sql('SELECT status FROM tb_blog_comments WHERE id=%s;' % target) == '2'
    assert sql('SELECT COUNT(*) FROM tb_blog_comments WHERE id IN (%s,%s) AND status=0;' % (parent, sibling)) == '2'
    assert all(retained(row) == value for row, value in before.items())
    audit = sql("SELECT CONCAT(operator_user_id,':',reason,':',from_status,':',to_status,':',root_comment_id) "
                "FROM tb_blog_reply_hide_audit WHERE reply_id=%s AND blog_id=%d AND create_time IS NOT NULL;" % (target, blog))
    assert audit == '900112:retained first reply reason:0:2:' + parent
    sql((ROOT / 'src/main/resources/db/migration/V013__add_blog_reply_hide_audit.sql').read_text('utf-8'))
    assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % target) == '1'
    page, _ = request(base, '/blog-comments/' + parent + '/replies?size=50', headers=author)
    assert [item['id'] for item in json.loads(page)['data']['items']] == [sibling]
    request(base, '/blog-comments/replies/' + target, 404, 'DELETE', headers=author)
    request(base, '/admin/blog-comments/' + target + '/hide-audit', 404, headers=admin)
    page, _ = request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % blog, headers=admin)
    assert json.loads(page)['data'] == {'items': [], 'hasNext': False}
    own = reply(parent, admin)
    request(base, route(own), 200, 'POST', json.dumps({'reason': 'x' * 255}).encode(), admin)
    assert sql('SELECT CHAR_LENGTH(reason) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % own) == '255'
    check('ADMIN reply hide: real roles/identity, large IDs, reason/forged-field boundaries, first receipt wins, exact one decrement and root-only audit reads')

    # Import malformed legacy rows without changing counters, then prove the new use case leaves them untouched.
    other_blog = post()
    other_root = root(other_blog)
    missing = 9223372036854775000
    rejected = [(1, parent, parent, blog), (2, parent, parent, blog),
                (3, parent, parent, blog), ('NULL', parent, parent, blog),
                (0, parent, 0, blog), (0, 0, parent, blog),
                (0, sibling, sibling, blog), (0, missing, missing, blog),
                (0, other_root, other_root, blog), (0, parent, parent, missing)]
    for state, parent_id, answer_id, owner_blog in rejected:
        excluded = sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
                       "VALUES(900110,%s,%s,%s,'synthetic excluded reply hide',%s); SELECT LAST_INSERT_ID();" %
                       (owner_blog, parent_id, answer_id, state))
        snapshot = sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % excluded)
        initial_count = count(blog)
        request(base, route(excluded), 404, 'POST', payload, admin)
        assert sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % excluded) == snapshot
        assert count(blog) == initial_count
        assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % excluded) == '0'
    for excluded in (parent, missing):
        request(base, route(excluded), 404, 'POST', payload, admin)
    for boundary in ('hidden-root', 'withdrawn-root', 'withdrawn-post'):
        owned_blog = post()
        owned_root = root(owned_blog)
        child = reply(owned_root)
        if boundary == 'hidden-root':
            request(base, '/admin/blog-comments/' + owned_root + '/hide', 200, 'POST', payload, admin)
        elif boundary == 'withdrawn-root':
            request(base, '/blog-comments/' + owned_root, 200, 'DELETE', headers=author)
        else:
            request(base, '/blog/%d' % owned_blog, 200, 'DELETE', headers=author)
        previous = int(count(owned_blog))
        request(base, route(child), 200, 'POST', payload, admin)
        request(base, route(child), 200, 'POST', payload, headers[900113])
        assert count(owned_blog) == str(previous - 1)
        assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % child) == '1'
    for legacy in ('NULL', '0', '1', '7'):
        owned_blog = post()
        child = reply(root(owned_blog))
        sql('UPDATE tb_blog SET comments=%s WHERE id=%d;' % (legacy, owned_blog))
        for _ in range(2):
            request(base, route(child), 200, 'POST', payload, admin)
        assert count(owned_blog) == str(max(0, int(legacy if legacy != 'NULL' else '0') - 1))
    check('Reply moderation: malformed/legacy/orphan/cross-post rows remain unchanged; hidden/withdrawn roots and posts remain moderatable; NULL/0/1/7 counter floors')

    owned_blog = post()
    owned_root = root(owned_blog)
    child, sibling = reply(owned_root), reply(owned_root)
    before = {row: sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % row)
              for row in (owned_root, child, sibling)}
    child_metadata = retained(child)
    for name, definition, expected, code in (
            ('synthetic_reply_hide_status', 'BEFORE UPDATE ON tb_blog_comments FOR EACH ROW '
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private reply status'", 500, 'INTERNAL_ERROR'),
            ('synthetic_reply_hide_counter', 'BEFORE UPDATE ON tb_blog FOR EACH ROW '
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private reply counter'", 500, 'INTERNAL_ERROR'),
            ('synthetic_reply_hide_audit', 'BEFORE INSERT ON tb_blog_reply_hide_audit FOR EACH ROW '
             "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private reply audit'", 500, 'INTERNAL_ERROR'),
            ('synthetic_reply_hide_zero', 'BEFORE UPDATE ON tb_blog_comments FOR EACH ROW '
             'DELETE FROM tb_blog WHERE id=NEW.blog_id', 422, 'OPERATION_FAILED')):
        sql('CREATE TRIGGER ' + name + ' ' + definition + ';')
        try:
            result, _ = request(base, route(child), expected, 'POST', payload, admin)
            result = json.loads(result)
            assert result['success'] is False and result['errorCode'] == code
            assert 'synthetic private' not in json.dumps(result)
            assert count(owned_blog) == '3'
            assert all(sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % row) == value
                       for row, value in before.items())
            assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % child) == '0'
        finally:
            sql('DROP TRIGGER ' + name + ';')
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(request, base, route(child), 200, 'POST', payload, admin) for _ in range(4)]
        assert all(json.loads(future.result(timeout=20)[0]) == {'success': True} for future in futures)
    assert count(owned_blog) == '2'
    assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % child) == '1'
    assert retained(child) == child_metadata
    assert sql('SELECT status FROM tb_blog_comments WHERE id=%s;' % child) == '2'
    assert sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % owned_root) == before[owned_root]
    assert sql('SELECT * FROM tb_blog_comments WHERE id=%s;' % sibling) == before[sibling]
    check('Reply hide real status/counter/audit SQL errors and zero-row 422 roll back all state; four concurrent HTTP retries commit one hide and receipt')


def verify_reply_hide_audit_query(base):
    """Read retained V013 metadata through real roles, independently from current content and V008."""
    blog_id, root_id, reply_id, sibling_id, operator_id, audit_id = (
        9007199254900001, 9007199254900003, 9007199254900007,
        9007199254900011, 9007199254900005, 9007199254900009)
    actors = ((900120, 'ADMIN'), (900121, 'USER'), (900122, 'MERCHANT'),
              (900123, 'ADMIN'), (900124, None), (operator_id, 'ADMIN'))
    headers = {}
    for index, (user_id, role) in enumerate(actors):
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'%011d','synthetic-reply-audit-reader');" %
            (user_id, 900120 + index))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-reply-audit-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900123;"
        "INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
        "VALUES(%d,NULL,900121,'synthetic retained audit post','','private post text',17,3);"
        "INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,status) VALUES"
        "(%d,900121,%d,0,0,'private retained root',0),"
        "(%d,900121,%d,%d,%d,'private retained target',0),"
        "(%d,900122,%d,%d,%d,'private retained sibling',0);"
        "ALTER TABLE tb_blog_reply_hide_audit AUTO_INCREMENT=%d;" %
        (blog_id, root_id, blog_id, reply_id, blog_id, root_id, root_id,
         sibling_id, blog_id, root_id, root_id, audit_id))
    admin = headers[900120]
    route = '/admin/blog-comments/replies/%d/hide-audit' % reply_id
    hide = '/admin/blog-comments/replies/%d/hide' % reply_id
    request(base, hide, 200, 'POST', b'{"reason":"original synthetic reply reason"}', headers[operator_id])
    request(base, route, 401)
    for user_id in (900121, 900122):
        request(base, route, 403, headers=headers[user_id])
    for user_id in (900123, 900124):
        request(base, route, 401, headers=headers[user_id])
    sql("UPDATE tb_user_role SET role='USER' WHERE user_id=%d;" % operator_id)
    request(base, route, 403, headers=headers[operator_id])
    sql("UPDATE tb_user_role SET role='ADMIN' WHERE user_id=%d;" % operator_id)
    for invalid in ('0', '-1', 'bad', '9223372036854775808'):
        request(base, '/admin/blog-comments/replies/' + invalid + '/hide-audit', 400, headers=admin)

    def read():
        body, _ = request(base, route, headers=admin)
        result = json.loads(body)
        assert set(result) == {'success', 'data'} and result['success'] is True
        data = result['data']
        assert set(data) == {'id', 'replyId', 'blogId', 'rootCommentId', 'operatorUserId',
                             'reason', 'fromStatus', 'toStatus', 'createTime'}
        for field, expected in (('id', audit_id), ('replyId', reply_id), ('blogId', blog_id),
                                ('rootCommentId', root_id), ('operatorUserId', operator_id)):
            assert isinstance(data[field], str) and data[field] == str(expected)
        assert data['reason'] == 'original synthetic reply reason'
        assert data['fromStatus'] == 0 and data['toStatus'] == 2
        expected_time = sql("SELECT DATE_FORMAT(create_time,'%Y-%m-%dT%H:%i:%s') "
                            'FROM tb_blog_reply_hide_audit WHERE reply_id=' + str(reply_id) + ';')
        assert data['createTime'] == expected_time
        return data

    first = read()
    request(base, hide, 200, 'POST', b'{"reason":"later reason must not replace"}', admin)
    assert read() == first
    assert sql('SELECT comments FROM tb_blog WHERE id=%d;' % blog_id) == '2'
    assert sql('SELECT status FROM tb_blog_comments WHERE id=%d;' % sibling_id) == '0'
    request(base, '/admin/blog-comments/%d/hide-audit' % reply_id, 404, headers=admin)
    request(base, '/admin/blog-comments/replies/%d/hide-audit' % root_id, 404, headers=admin)
    page, _ = request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % blog_id, headers=admin)
    assert json.loads(page)['data'] == {'items': [], 'hasNext': False}
    request(base, '/admin/blog-comments/%d/hide' % root_id, 200, 'POST',
            b'{"reason":"independent root reason"}', admin)
    request(base, '/admin/blog-comments/replies/%d/hide-audit' % root_id, 404, headers=admin)
    page, _ = request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % blog_id, headers=admin)
    assert [item['commentId'] for item in json.loads(page)['data']['items']] == [str(root_id)]
    sql((ROOT / 'src/main/resources/db/migration/V013__add_blog_reply_hide_audit.sql').read_text('utf-8'))
    assert read() == first
    check('Reply audit GET: real ADMIN/current-role isolation, five lossless large string IDs, exact nine-field metadata, first receipt retention and V008 separation')

    for state in (0, 2, 3):
        missing = sql("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,status) "
                      "VALUES(900121,%d,%d,%d,'synthetic no receipt',%d); SELECT LAST_INSERT_ID();" %
                      (blog_id, root_id, root_id, state))
        request(base, '/admin/blog-comments/replies/' + missing + '/hide-audit', 404, headers=admin)
        assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%s;' % missing) == '0'
    request(base, '/admin/blog-comments/replies/9223372036854775807/hide-audit', 404, headers=admin)
    before = sql('SELECT * FROM tb_blog WHERE id=%d; SELECT * FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;'
                 'SELECT * FROM tb_blog_reply_hide_audit WHERE reply_id=%d;' % (blog_id, blog_id, reply_id))
    triggers = []
    try:
        for suffix, table in (('reply', 'tb_blog_comments'), ('post', 'tb_blog'), ('audit', 'tb_blog_reply_hide_audit')):
            name = 'synthetic_reply_audit_readonly_' + suffix
            sql('CREATE TRIGGER ' + name + ' BEFORE UPDATE ON ' + table + " FOR EACH ROW "
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private unexpected update';")
            triggers.append(name)
        assert read() == first and read() == first
        assert sql('SELECT * FROM tb_blog WHERE id=%d; SELECT * FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;'
                   'SELECT * FROM tb_blog_reply_hide_audit WHERE reply_id=%d;' % (blog_id, blog_id, reply_id)) == before
    finally:
        for name in triggers:
            sql('DROP TRIGGER ' + name + ';')
    sql('RENAME TABLE tb_blog_reply_hide_audit TO synthetic_reply_audit_query_unavailable;')
    try:
        body, _ = request(base, route, 500, headers=admin)
        error = json.loads(body)
        assert error['success'] is False and error['errorCode'] == 'INTERNAL_ERROR'
        assert error['errorMsg'] == '服务器异常' and 'data' not in error
        request(base, '/admin/blog-comments/%d/hide-audit' % root_id, headers=admin)
    finally:
        sql('RENAME TABLE synthetic_reply_audit_query_unavailable TO tb_blog_reply_hide_audit;')
    assert read() == first
    # Only rows explicitly created by this helper in the owned private project; retained audit is not a join.
    sql('DELETE FROM tb_blog_comments WHERE blog_id=%d; DELETE FROM tb_blog WHERE id=%d;'
        'DELETE FROM tb_user_role WHERE user_id=%d; DELETE FROM tb_user WHERE id=%d;' %
        (blog_id, blog_id, operator_id, operator_id))
    request(base, route, 401, headers=headers[operator_id])
    assert read() == first
    assert sql('SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=%d;' % reply_id) == '1'
    check('Reply audit reads never synthesize legacy history or write state/counts; SQL fault is generic 500; retained metadata survives removal of synthetic reply/root/post/operator')


def verify_reply_hide_audit_pages(base):
    """Per-post V013 pages; actual hides first, explicitly imported metadata for 20/50 lookahead."""
    blog_id, other_blog, root_id, other_root, operator, audit_start = (
        9007199254950001, 9007199254950002, 9007199254950010,
        9007199254950011, 9007199254950005, 9007199254950101)
    replies = (9007199254950021, 9007199254950022, 9007199254950023, 9007199254950024)
    headers = {}
    actors = ((900130, 'ADMIN'), (900131, 'USER'), (900132, 'MERCHANT'),
              (900133, 'ADMIN'), (900134, None), (operator, 'ADMIN'))
    for index, (user_id, role) in enumerate(actors):
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'%011d','synthetic-reply-audit-page');" %
            (user_id, 900130 + index))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-reply-audit-page-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=900133;")
    for post, parent, children in ((blog_id, root_id, replies[:3]), (other_blog, other_root, replies[3:])):
        sql("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
            "VALUES(%d,NULL,900131,'synthetic reply audit page','','retained page post',0,%d);"
            "INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,status) "
            "VALUES(%d,900131,%d,0,0,'retained page root',0);" % (post, len(children) + 1, parent, post))
        for child in children:
            sql("INSERT INTO tb_blog_comments(id,user_id,blog_id,parent_id,answer_id,content,status) "
                "VALUES(%d,900131,%d,%d,%d,'retained page reply',0);" % (child, post, parent, parent))
    sql('ALTER TABLE tb_blog_reply_hide_audit AUTO_INCREMENT=%d;' % audit_start)
    admin = headers[900130]
    prefix = '/admin/blog-comments/replies/hide-audits/of/blog/'
    route = prefix + str(blog_id)
    for child in (replies[1], replies[3], replies[0], replies[2]):
        request(base, '/admin/blog-comments/replies/%d/hide' % child, 200, 'POST',
                json.dumps({'reason': 'synthetic page reason %d' % child}).encode(), headers[operator])
    request(base, '/admin/blog-comments/%d/hide' % root_id, 200, 'POST', b'{"reason":"root only"}', admin)
    request(base, route, 401)
    for user_id in (900131, 900132):
        request(base, route, 403, headers=headers[user_id])
    for user_id in (900133, 900134):
        request(base, route, 401, headers=headers[user_id])
    sql("UPDATE tb_user_role SET role='USER' WHERE user_id=%d;" % operator)
    request(base, route, 403, headers=headers[operator])
    sql("UPDATE tb_user_role SET role='ADMIN' WHERE user_id=%d;" % operator)
    for invalid in ('0', '-1', 'bad', '9223372036854775808'):
        request(base, prefix + invalid, 400, headers=admin)
    for query in ('size=0', 'size=51', 'size=-1', 'size=bad', 'beforeId=0', 'beforeId=-1',
                  'beforeId=bad', 'beforeId=9223372036854775808'):
        request(base, route + '?' + query, 400, headers=admin)

    def read_page(query='', post=blog_id):
        body, _ = request(base, prefix + str(post) + query, headers=admin)
        result = json.loads(body)
        assert set(result) == {'success', 'data'} and result['success'] is True
        page = result['data']
        assert set(page) == ({'items', 'hasNext', 'nextBeforeId'} if page['hasNext'] else {'items', 'hasNext'})
        for item in page['items']:
            assert set(item) == {'id', 'replyId', 'blogId', 'rootCommentId', 'operatorUserId',
                                 'reason', 'fromStatus', 'toStatus', 'createTime'}
            for field in ('id', 'replyId', 'blogId', 'rootCommentId', 'operatorUserId'):
                assert isinstance(item[field], str) and item[field].isdigit()
            assert item['blogId'] == str(post) and item['operatorUserId'] == str(operator)
            assert item['fromStatus'] == 0 and item['toStatus'] == 2
        return page

    expected = []
    for child in (replies[2], replies[0], replies[1]):
        body, _ = request(base, '/admin/blog-comments/replies/%d/hide-audit' % child, headers=admin)
        expected.append(json.loads(body)['data'])
    assert read_page()['items'] == expected
    first = read_page('?size=2')
    assert first['items'] == expected[:2] and first['hasNext'] is True
    assert first['nextBeforeId'] == expected[1]['id']
    tail = read_page('?size=2&beforeId=' + first['nextBeforeId'])
    assert tail == {'items': expected[2:], 'hasNext': False}
    assert read_page('?beforeId=' + expected[-1]['id']) == {'items': [], 'hasNext': False}
    assert read_page('?size=50&ignored=client-value')['items'] == expected
    assert read_page(post=9223372036854775807) == {'items': [], 'hasNext': False}
    assert len(read_page(post=other_blog)['items']) == 1
    root_page, _ = request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % blog_id, headers=admin)
    assert [item['commentId'] for item in json.loads(root_page)['data']['items']] == [str(root_id)]
    sql((ROOT / 'src/main/resources/db/migration/V014__add_blog_reply_hide_audit_page_index.sql').read_text('utf-8'))
    assert read_page()['items'] == expected
    check('Reply audit pages: real ADMIN/current-role isolation, nine-field whitelist, five large string IDs, audit-ID cursor rather than reply order, cross-post/V008 separation and V014 repeat')

    imported_blog = 9007199254950004
    imported_start = 9007199254951000
    # These 51 rows are synthetic imported metadata, NOT 51 actual hide operations.
    values = ["(%d,%d,%d,%d,%d,'synthetic imported metadata only',0,2)" %
              (imported_start + index, imported_start + 1000 + index, imported_blog, root_id, operator)
              for index in range(51)]
    sql('INSERT INTO tb_blog_reply_hide_audit(id,reply_id,blog_id,root_comment_id,operator_user_id,reason,from_status,to_status) VALUES' + ','.join(values) + ';')
    expected_ids = [str(imported_start + index) for index in reversed(range(51))]
    default = read_page(post=imported_blog)
    assert len(default['items']) == 20 and default['hasNext'] is True
    assert default['nextBeforeId'] == expected_ids[19]
    maximum = read_page('?size=50', imported_blog)
    assert [item['id'] for item in maximum['items']] == expected_ids[:50] and maximum['hasNext'] is True
    assert maximum['nextBeforeId'] == expected_ids[49]
    assert [item['id'] for item in read_page('?size=50&beforeId=' + maximum['nextBeforeId'], imported_blog)['items']] == expected_ids[50:]
    exact = read_page('?size=50&beforeId=' + expected_ids[0], imported_blog)
    assert [item['id'] for item in exact['items']] == expected_ids[1:] and exact['hasNext'] is False
    collected, query = [], ''
    for _ in range(4):
        page = read_page(query, imported_blog)
        collected.extend(item['id'] for item in page['items'])
        if not page['hasNext']:
            break
        query = '?beforeId=' + page['nextBeforeId']
    assert collected == expected_ids and len(set(collected)) == 51
    snapshot = sql('SELECT * FROM tb_blog_reply_hide_audit WHERE blog_id IN (%d,%d) ORDER BY id;'
                   'SELECT * FROM tb_blog WHERE id=%d; SELECT * FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;' %
                   (blog_id, imported_blog, blog_id, blog_id))
    triggers = []
    try:
        for suffix, table in (('audit', 'tb_blog_reply_hide_audit'), ('post', 'tb_blog'), ('reply', 'tb_blog_comments')):
            name = 'synthetic_reply_audit_page_readonly_' + suffix
            sql('CREATE TRIGGER ' + name + ' BEFORE UPDATE ON ' + table + " FOR EACH ROW "
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private unexpected page write';")
            triggers.append(name)
        assert read_page()['items'] == expected
        assert len(read_page(post=imported_blog)['items']) == 20
        assert sql('SELECT * FROM tb_blog_reply_hide_audit WHERE blog_id IN (%d,%d) ORDER BY id;'
                   'SELECT * FROM tb_blog WHERE id=%d; SELECT * FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;' %
                   (blog_id, imported_blog, blog_id, blog_id)) == snapshot
    finally:
        for name in triggers:
            sql('DROP TRIGGER ' + name + ';')
    sql('RENAME TABLE tb_blog_reply_hide_audit TO synthetic_reply_audit_pages_unavailable;')
    try:
        body, _ = request(base, route, 500, headers=admin)
        error = json.loads(body)
        assert error['errorCode'] == 'INTERNAL_ERROR' and error['errorMsg'] == '服务器异常' and 'data' not in error
        request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % blog_id, headers=admin)
    finally:
        sql('RENAME TABLE synthetic_reply_audit_pages_unavailable TO tb_blog_reply_hide_audit;')
    assert read_page()['items'] == expected
    sql('DELETE FROM tb_blog_comments WHERE blog_id=%d; DELETE FROM tb_blog WHERE id=%d;'
        'DELETE FROM tb_user_role WHERE user_id=%d; DELETE FROM tb_user WHERE id=%d;' %
        (blog_id, blog_id, operator, operator))
    request(base, route, 401, headers=headers[operator])
    assert read_page()['items'] == expected
    check('Reply audit page bounds: imported 51 metadata rows exercise default20/max50 lookahead without frozen-snapshot claim; reads do not write, SQL faults are generic, orphan metadata remains readable')


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


def verify_post_author_edit(base, tokens):
    """Author-only edits against real HTTP/SQL/Redis; fixtures belong to this fresh project."""
    actors = [(900040, 'USER'), (900041, 'MERCHANT'), (900042, 'ADMIN'),
              (900043, 'USER'), (900044, 'USER'), (900045, 'MERCHANT'),
              (900046, None), (900047, 'AUDITOR'), (900048, 'ADMIN')]
    headers = {}
    for user_id, role in actors:
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'000000000%02d',"
            "'synthetic-edit-actor','%s');" % (user_id, user_id - 900000,
                                               'DISABLED' if user_id == 900048 else 'ACTIVE'))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-post-edit-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    sql("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(900043,900040),(900043,900041),(900043,900042);")
    posts = []
    for user_id in (900040, 900041, 900042):
        body, _ = request(base, '/blog', 200, 'POST', json.dumps(
            {'title': 'synthetic old edit title', 'content': 'synthetic old edit content'}).encode(), headers[user_id])
        posts.append((json.loads(body)['data'], user_id))
    body, _ = request(base, '/blog', 200, 'POST', json.dumps(
        {'shopId': 1, 'title': 'synthetic store edit', 'images': '/synthetic-store.png',
         'content': 'synthetic old store content'}).encode(), headers[900040])
    store_id = json.loads(body)['data']
    first = posts[0][0]
    body, _ = request(base, '/blog-comments', 200, 'POST', json.dumps(
        {'blogId': first, 'content': 'synthetic retained edit root'}).encode(), headers[900040])
    root_id = json.loads(body)['data']['id']
    body, _ = request(base, '/blog-comments/%s/replies' % root_id, 200, 'POST', json.dumps(
        {'content': 'synthetic retained edit reply'}).encode(), headers[900041])
    reply_id = json.loads(body)['data']['id']
    request(base, '/admin/blog-comments/%s/hide' % root_id, 200, 'POST', json.dumps(
        {'reason': 'synthetic retained edit audit'}).encode(), headers[900042])
    request(base, '/blog/like/%d' % first, 200, 'PUT', headers=headers[900041])
    assert sql("SELECT CONCAT(liked,':',comments) FROM tb_blog WHERE id=%d;" % first) == '1:1'
    audit_before = sql("SELECT CONCAT(id,':',comment_id,':',operator_user_id,':',reason) "
                       "FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % root_id)
    feed_before = redis('ZRANGE', 'feed:900043', '0', '-1', 'WITHSCORES')
    likes_before = redis('ZRANGE', 'blog:liked:%d' % first, '0', '-1', 'WITHSCORES')
    payload = {'title': 'synthetic edited title', 'images': None,
               'content': "synthetic edited <script>text</script> ' ; --",
               'id': 999999, 'userId': 900044, 'shopId': 999999, 'liked': 999, 'comments': 888,
               'createTime': '2000-01-01T00:00:00', 'updateTime': '2000-01-01T00:00:00'}
    for post_id, user_id in posts:
        route = '/blog/%d' % post_id
        create_time = sql("SELECT create_time FROM tb_blog WHERE id=%d;" % post_id)
        request(base, route, 401, 'PUT', json.dumps(payload).encode(), {'Content-Type': 'application/json'})
        for invalid_user in (900046, 900047, 900048):
            request(base, route, 401, 'PUT', json.dumps(payload).encode(), headers[invalid_user])
        for other in (headers[900044], headers[900045], {'authorization': tokens[1], 'Content-Type': 'application/json'}):
            request(base, route, 403, 'PUT', json.dumps(payload).encode(), other)
        body, _ = request(base, route, 200, 'PUT', json.dumps(payload).encode(), headers[user_id])
        assert json.loads(body) == {'success': True}
        assert sql("SELECT COUNT(*) FROM tb_blog WHERE id=%d AND user_id=%d AND shop_id IS NULL "
                   "AND images='' AND title='synthetic edited title';" % (post_id, user_id)) == '1'
        assert sql("SELECT create_time FROM tb_blog WHERE id=%d;" % post_id) == create_time
        detail, _ = request(base, route, headers=headers[user_id])
        detail = json.loads(detail)['data']
        assert detail['images'] == '' and detail['content'] == payload['content']
        assert detail['userId'] == user_id and 'shopId' not in detail
    assert sql("SELECT CONCAT(liked,':',comments) FROM tb_blog WHERE id=%d;" % first) == '1:1'
    assert sql("SELECT CONCAT(status,':',content) FROM tb_blog_comments WHERE id=%s;" % root_id) == '2:synthetic retained edit root'
    assert sql("SELECT CONCAT(status,':',content) FROM tb_blog_comments WHERE id=%s;" % reply_id) == '0:synthetic retained edit reply'
    assert sql("SELECT CONCAT(id,':',comment_id,':',operator_user_id,':',reason) "
               "FROM tb_blog_comment_hide_audit WHERE comment_id=%s;" % root_id) == audit_before
    assert redis('ZRANGE', 'feed:900043', '0', '-1', 'WITHSCORES') == feed_before
    assert redis('ZRANGE', 'blog:liked:%d' % first, '0', '-1', 'WITHSCORES') == likes_before
    body, _ = request(base, '/blog/of/follow?lastId=%d&offset=0' % (int(time.time() * 1000) + 60000), headers=headers[900043])
    rows = json.loads(body)['data']['list']
    assert len(rows) == 2 and all(row['id'] in [p[0] for p in posts] + [store_id] for row in rows)
    for row in rows:
        if row['id'] != store_id:
            assert row['content'] == payload['content'] and row['images'] == ''
    check('post edits: author-only three roles, no ADMIN override, immutable ownership/association/counters/comments/audit/Feed score and current content reads')

    store_payload = {'title': 'synthetic updated store', 'content': 'synthetic updated store content', 'shopId': None}
    for images in (None, '', ' \t'):
        store_payload['images'] = images
        request(base, '/blog/%d' % store_id, 400, 'PUT', json.dumps(store_payload).encode(), headers[900040])
    store_payload['images'] = '  /synthetic-new-store.png  '
    request(base, '/blog/%d' % store_id, 200, 'PUT', json.dumps(store_payload).encode(), headers[900040])
    assert sql("SELECT shop_id FROM tb_blog WHERE id=%d;" % store_id) == '1'
    assert sql("SELECT images FROM tb_blog WHERE id=%d;" % store_id) == store_payload['images'].strip()  # CLI strips output; HTTP below preserves it.
    body, _ = request(base, '/blog/%d' % store_id, headers=headers[900040])
    assert json.loads(body)['data']['images'] == store_payload['images']
    for invalid_id in ('0', '-1', 'bad', '9223372036854775808'):
        request(base, '/blog/' + invalid_id, 400, 'PUT', json.dumps(payload).encode(), headers[900040])
    request(base, '/blog/9223372036854775807', 404, 'PUT', json.dumps(payload).encode(), headers[900040])
    invalids = [dict(payload, title=' '), dict(payload, title='t' * 256), dict(payload, content=' '),
                dict(payload, content='c' * 2049), dict(payload, images=' ' * 2049), dict(payload, images='p' * 2049),
                {'title': 'missing replacement body'}, {}]
    for invalid in invalids:
        request(base, '/blog/%d' % first, 400, 'PUT', json.dumps(invalid).encode(), headers[900040])
    snapshot = sql("SELECT CONCAT(title,':',images,':',content,':',liked,':',comments,':',update_time) FROM tb_blog WHERE id=%d;" % first)
    sql("DELIMITER $$\nCREATE TRIGGER fail_owned_post_edit BEFORE UPDATE ON tb_blog FOR EACH ROW BEGIN "
        "IF OLD.id=%d THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private edit fault'; "
        "END IF; END$$\nDELIMITER ;\n" % first)
    try:
        # Exact normalized replay must not issue an UPDATE, even while the trigger rejects one.
        request(base, '/blog/%d' % first, 200, 'PUT', json.dumps(payload).encode(), headers[900040])
        failure = dict(payload, title='synthetic changed edit should roll back')
        body, _ = request(base, '/blog/%d' % first, 500, 'PUT', json.dumps(failure).encode(), headers[900040])
        error = json.loads(body)
        assert error['errorCode'] == 'INTERNAL_ERROR' and error['errorMsg'] == '服务器异常'
        assert b'synthetic private edit fault' not in body and b'SIGNAL' not in body
    finally:
        sql('DROP TRIGGER IF EXISTS fail_owned_post_edit;')
    assert sql("SELECT CONCAT(title,':',images,':',content,':',liked,':',comments,':',update_time) FROM tb_blog WHERE id=%d;" % first) == snapshot
    assert redis('ZRANGE', 'feed:900043', '0', '-1', 'WITHSCORES') == feed_before
    check('post edit boundaries: stored store association, full-replacement validation, exact no-op under UPDATE-rejecting trigger, generic SQL failure and rollback')


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


def verify_post_author_withdrawal(base, tokens):
    """Synthetic owned fixtures: retained data, closed visibility, raw Feed cursor and failures."""
    actors = [(900060, 'USER'), (900061, 'MERCHANT'), (900062, 'ADMIN'), (900063, 'USER'),
              (900064, None), (900065, 'AUDITOR'), (900066, 'ADMIN')]
    headers = {}
    for user_id, role in actors:
        sql("INSERT INTO tb_user(id,phone,nick_name,status) VALUES(%d,'000000000%02d',"
            "'synthetic-withdraw-actor','%s');" % (user_id, user_id - 900000,
                                                   'DISABLED' if user_id == 900066 else 'ACTIVE'))
        if role:
            sql("INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, role))
        token = 'synthetic-post-withdraw-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    sql("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(900063,900060),(900063,900061),(900063,900062);")
    # Keep these three owned intents pending so the former fixture/worker race is reproducible.
    redis('SET', 'feed:900063', 'synthetic withdrawal fixture blocker')
    posts = []
    for user_id in (900060, 900061, 900062):
        payload = {'title': 'synthetic withdrawal post', 'content': 'synthetic retained body', 'withdrawn': 1}
        if user_id == 900061:
            payload.update(shopId=1, images='/synthetic-retained.png')
        body, _ = request(base, '/blog', 200, 'POST', json.dumps(payload).encode(), headers[user_id])
        posts.append(int(json.loads(body)['data']))
    first = posts[0]
    body, _ = request(base, '/blog-comments', 200, 'POST', json.dumps(
        {'blogId': first, 'content': 'synthetic retained withdrawal root'}).encode(), headers[900061])
    root = json.loads(body)['data']['id']
    body, _ = request(base, '/blog-comments/%s/replies' % root, 200, 'POST', json.dumps(
        {'content': 'synthetic retained withdrawal reply'}).encode(), headers[900061])
    reply = json.loads(body)['data']['id']
    body, _ = request(base, '/blog-comments', 200, 'POST', json.dumps(
        {'blogId': first, 'content': 'synthetic retained hidden root'}).encode(), headers[900060])
    hidden = json.loads(body)['data']['id']
    request(base, '/admin/blog-comments/%s/hide' % hidden, 200, 'POST', json.dumps(
        {'reason': 'synthetic retained withdrawal audit'}).encode(), headers[900062])
    request(base, '/blog/like/%d' % first, 200, 'PUT', headers=headers[900061])
    # Make hidden candidates rank first if the generated hot query loses its visibility predicate.
    sql('UPDATE tb_blog SET liked=9000 WHERE id IN (%s);' % ','.join(map(str, posts)))
    assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id=900063;') == '3'
    delivery_scores = {int(row.split('\t')[0]): int(row.split('\t')[1]) for row in sql(
        'SELECT blog_id,score_millis FROM tb_blog_feed_delivery WHERE recipient_user_id=900063;').splitlines()}
    redis('DEL', 'feed:900063')  # Only the synthetic blocker above; this actor has no prior Feed data.
    redis('ZADD', 'feed:900063', '3000', str(posts[0]), '2000', str(posts[1]), '1000', str(posts[2]))
    deadline = time.monotonic() + 25
    while sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id=900063;') != '0':
        if time.monotonic() >= deadline:
            raise AssertionError('synthetic withdrawal fixture intents did not settle')
        time.sleep(.25)
    # The recovery worker correctly restores publication scores, invalidating a premature low-score fixture.
    for post_id in posts:
        assert int(float(redis('ZSCORE', 'feed:900063', str(post_id)))) == delivery_scores[post_id] > 3000
    print('Confirmed three synthetic withdrawal fixture scores are restored by pending delivery replay', flush=True)
    # Only after ACK removes every owned intent can this frozen cursor scenario install synthetic scores.
    redis('ZADD', 'feed:900063', '3000', str(posts[0]), '2000', str(posts[1]), '1000', str(posts[2]))
    retained = sql('SELECT id,user_id,shop_id,title,images,content,liked,comments,create_time FROM tb_blog '
                   'WHERE id IN (%s) ORDER BY id;' % ','.join(map(str, posts)))
    comments_before = sql('SELECT id,user_id,content,status FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;' % first)
    audit_before = sql('SELECT * FROM tb_blog_comment_hide_audit WHERE comment_id=%s;' % hidden)
    feed_before = redis('ZRANGE', 'feed:900063', '0', '-1', 'WITHSCORES')
    likes_before = redis('ZRANGE', 'blog:liked:%d' % first, '0', '-1', 'WITHSCORES')
    for post_id, user_id in zip(posts, (900060, 900061, 900062)):
        route = '/blog/%d' % post_id
        request(base, route, 401, 'DELETE')
        for invalid_actor in (900064, 900065, 900066):
            request(base, route, 401, 'DELETE', headers=headers[invalid_actor])
        request(base, route, 403, 'DELETE', headers={'authorization': tokens[1]})
        for other in (900060, 900061, 900062):
            if other != user_id:
                request(base, route, 403, 'DELETE', headers=headers[other])
        assert sql('SELECT withdrawn FROM tb_blog WHERE id=%d;' % post_id) == '0'
        body, _ = request(base, route, 200, 'DELETE', headers=headers[user_id])
        assert json.loads(body) == {'success': True}
        request(base, route, 200, 'DELETE', headers=headers[user_id])
        request(base, route, 403, 'DELETE', headers={'authorization': tokens[1]})
        if post_id == posts[1]:
            body, _ = request(base, '/blog/of/follow?lastId=3000&offset=0', headers=headers[900063])
            page = json.loads(body)['data']
            assert page['list'] == [] and page['minTime'] == 2000 and page['offset'] == 1
            body, _ = request(base, '/blog/of/follow?lastId=2000&offset=1', headers=headers[900063])
            page = json.loads(body)['data']
            assert [row['id'] for row in page['list']] == [posts[2]] and 'withdrawn' not in page['list'][0]
    assert sql('SELECT id,user_id,shop_id,title,images,content,liked,comments,create_time FROM tb_blog '
               'WHERE id IN (%s) ORDER BY id;' % ','.join(map(str, posts))) == retained
    assert sql('SELECT id,user_id,content,status FROM tb_blog_comments WHERE blog_id=%d ORDER BY id;' % first) == comments_before
    assert sql('SELECT * FROM tb_blog_comment_hide_audit WHERE comment_id=%s;' % hidden) == audit_before
    assert redis('ZRANGE', 'feed:900063', '0', '-1', 'WITHSCORES') == feed_before
    assert redis('ZRANGE', 'blog:liked:%d' % first, '0', '-1', 'WITHSCORES') == likes_before
    for post_id, user_id in zip(posts, (900060, 900061, 900062)):
        for route in ('/blog/%d' % post_id, '/blog/likes/%d' % post_id):
            request(base, route, 404, headers=headers[user_id])
        request(base, '/blog/like/%d' % post_id, 404, 'PUT', headers=headers[user_id])
        request(base, '/blog/%d' % post_id, 404, 'PUT', json.dumps(
            {'title': 'must not edit', 'images': '/synthetic.png', 'content': 'must not edit'}).encode(), headers[user_id])
        for route in ('/blog/of/me', '/blog/of/user?id=%d' % user_id):
            body, _ = request(base, route, headers=headers[user_id])
            assert json.loads(body)['data'] == []
        request(base, '/blog-comments/of/blog/%d' % post_id, 404, headers=headers[user_id])
        request(base, '/blog-comments', 404, 'POST', json.dumps(
            {'blogId': post_id, 'content': 'must not insert'}).encode(), headers=headers[user_id])
    body, _ = request(base, '/blog/hot')
    assert all(row['id'] not in posts and 'withdrawn' not in row for row in json.loads(body)['data'])
    request(base, '/blog-comments/%s/replies' % root, 404, headers=headers[900061])
    request(base, '/blog-comments/%s/replies' % root, 404, 'POST', json.dumps(
        {'content': 'must not reply'}).encode(), headers=headers[900061])
    check('post withdrawal: three roles owner-only, retained data/Redis, closed reads/writes, filtered empty Feed page advances raw cursor')
    for invalid_id in ('0', '-1', 'bad', '9223372036854775808'):
        request(base, '/blog/' + invalid_id, 400, 'DELETE', headers=headers[900060])
    request(base, '/blog/9223372036854775807', 404, 'DELETE', headers=headers[900060])
    sql("CREATE TRIGGER reject_post_withdrawal BEFORE UPDATE ON tb_blog FOR EACH ROW "
        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private withdrawal fault';")
    try:
        timestamp = sql('SELECT update_time FROM tb_blog WHERE id=%d;' % first)
        request(base, '/blog/%d' % first, 200, 'DELETE', headers=headers[900060])
        assert sql('SELECT update_time FROM tb_blog WHERE id=%d;' % first) == timestamp
        body, _ = request(base, '/blog', 200, 'POST', json.dumps(
            {'title': 'synthetic failed withdrawal', 'content': 'must remain visible'}).encode(), headers=headers[900060])
        fault_post = int(json.loads(body)['data'])
        body, _ = request(base, '/blog/%d' % fault_post, 500, 'DELETE', headers=headers[900060])
        assert json.loads(body)['errorCode'] == 'INTERNAL_ERROR' and b'synthetic private withdrawal fault' not in body
        assert sql('SELECT withdrawn FROM tb_blog WHERE id=%d;' % fault_post) == '0'
        request(base, '/blog/%d' % fault_post, headers=headers[900060])
    finally:
        sql('DROP TRIGGER IF EXISTS reject_post_withdrawal;')
    request(base, '/blog-comments/replies/%s' % reply, 200, 'DELETE', headers=headers[900061])
    request(base, '/admin/blog-comments/%s/hide' % root, 200, 'POST', json.dumps(
        {'reason': 'synthetic hidden after post withdrawal'}).encode(), headers=headers[900062])
    request(base, '/admin/blog-comments/%s/hide-audit' % hidden, headers=headers[900062])
    body, _ = request(base, '/admin/blog-comments/hide-audits/of/blog/%d' % first, headers=headers[900062])
    assert len(json.loads(body)['data']['items']) == 2
    sql((ROOT / 'src/main/resources/db/migration/V010__add_blog_author_withdrawal.sql').read_text('utf-8'))
    assert sql('SELECT SUM(withdrawn) FROM tb_blog WHERE id IN (%s);' % ','.join(map(str, posts))) == '3'
    assert sql('SELECT * FROM tb_blog_comment_hide_audit WHERE comment_id=%s;' % hidden) == audit_before
    check('post withdrawal boundaries: exact repeat without UPDATE, sanitized SQL rollback, independent comment actions/audits, V010 repeat preservation')


def verify_follow_feed_cursor(base):
    """Frozen, project-owned Feed fixtures: real HTTP, Redis ordering and SQL visibility."""
    author, follower, other = 900070, 900071, 900072
    headers = {}
    for user_id in (author, follower, other):
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'000000000%02d','synthetic-feed-actor');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'USER');" % (user_id, user_id - 900000, user_id))
        token = 'synthetic-feed-cursor-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    posts = list(range(810001, 810008))
    for post_id in posts + [810010]:
        sql("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
            "VALUES(%d,NULL,%d,'synthetic feed cursor','','synthetic feed content',0,0);" % (post_id, author))
    key = 'feed:%d' % follower
    for post_id in posts:
        redis('ZADD', key, '1000', str(post_id))
    redis('ZADD', 'feed:%d' % other, '1000', '810010')

    def snapshot_feed():
        return redis('ZRANGE', key, '0', '-1', 'WITHSCORES')

    def snapshot_posts():
        return sql('SELECT id,user_id,shop_id,title,images,content,liked,comments,withdrawn,create_time,update_time '
                   'FROM tb_blog WHERE id IN (%s) ORDER BY id;' % ','.join(map(str, posts)))

    def walk(last_id, cursor_plan):
        offset = 0
        seen = []
        raw_pages = []
        for expected_time, expected_offset in cursor_plan:
            body, _ = request(base, '/blog/of/follow?lastId=%d&offset=%d' % (last_id, offset),
                              headers=headers[follower])
            page = json.loads(body)['data']
            assert page['minTime'] == expected_time and page['offset'] == expected_offset
            ids = [row['id'] for row in page['list']]
            assert len(ids) <= 2 and not (set(ids) & set(seen))
            assert all('withdrawn' not in row for row in page['list'])
            seen.extend(ids)
            raw_pages.append(ids)
            last_id, offset = page['minTime'], page['offset']
        body, _ = request(base, '/blog/of/follow?lastId=%d&offset=%d' % (last_id, offset),
                          headers=headers[follower])
        assert json.loads(body) == {'success': True}, 'terminal Redis batch is empty success without data'
        return seen, raw_pages

    before_feed, before_posts = snapshot_feed(), snapshot_posts()
    expected = list(map(int, redis('ZREVRANGEBYSCORE', key, '1000', '0').splitlines()))
    seen, pages = walk(1000, [(1000, 2), (1000, 4), (1000, 6), (1000, 7)])
    assert seen == expected and [len(page) for page in pages] == [2, 2, 2, 1]
    body, _ = request(base, '/blog/of/follow?lastId=1000', headers=headers[follower])
    assert [row['id'] for row in json.loads(body)['data']['list']] == expected[:2]
    assert snapshot_feed() == before_feed and snapshot_posts() == before_posts
    body, _ = request(base, '/blog/of/follow?lastId=1000', headers=headers[other])
    assert [row['id'] for row in json.loads(body)['data']['list']] == [810010]
    request(base, '/blog/of/follow?lastId=1000', 401)
    for query in ('lastId=0', 'lastId=-1', 'lastId=bad', 'lastId=9223372036854775808',
                  'lastId=1000&offset=-1', 'lastId=1000&offset=bad', 'lastId=1000&offset=2147483648', 'offset=0'):
        request(base, '/blog/of/follow?' + query, 400, headers=headers[follower])
    check('Feed cursors: seven equal scores yield offsets 2/4/6/7, exact Redis order, one-item tail, terminal empty success and account isolation')

    # Change only these fixtures between scenarios; each traversal itself has fixed membership/scores.
    for index, post_id in enumerate(expected):
        redis('ZADD', key, '2000' if index < 3 else '1000', str(post_id))
    plan = [(2000, 2), (1000, 1), (1000, 3), (1000, 4)]
    expected = list(map(int, redis('ZREVRANGEBYSCORE', key, '2000', '0').splitlines()))
    before_feed, before_posts = snapshot_feed(), snapshot_posts()
    seen, _ = walk(2000, plan)
    assert seen == expected and snapshot_feed() == before_feed and snapshot_posts() == before_posts
    body, _ = request(base, '/blog/of/follow?lastId=1000', headers=headers[follower])
    older = list(map(int, redis('ZREVRANGEBYSCORE', key, '1000', '0').splitlines()))
    assert [row['id'] for row in json.loads(body)['data']['list']] == older[:2]
    for post_id in expected[:4]:
        request(base, '/blog/%d' % post_id, 200, 'DELETE', headers=headers[author])
    before_feed, before_posts = snapshot_feed(), snapshot_posts()
    seen, pages = walk(2000, plan)
    assert pages[0] == [] and pages[1] == [] and seen == expected[4:]
    assert snapshot_feed() == before_feed and snapshot_posts() == before_posts
    assert sql('SELECT COUNT(*) FROM tb_blog WHERE id IN (%s) AND withdrawn=1;' % ','.join(map(str, posts))) == '4'
    body, _ = request(base, '/blog/of/follow?lastId=2000&offset=2147483647', headers=headers[follower])
    assert json.loads(body) == {'success': True}
    check('Feed cursors: score transitions reset offsets, smaller bound excludes future entries, withdrawn empty pages still advance raw cursor without writes')


def verify_blog_feed_delivery(base):
    """Commit/Redis/ACK faults against project-owned recipients, durable intents and fixed score."""
    author, bad, good, restart_recipient, restart_author = 900090, 900091, 900092, 900093, 900094
    headers = {}
    for user_id in (author, bad, good, restart_recipient, restart_author):
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'000000000%02d','synthetic-outbox-actor');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'USER');" % (user_id, user_id - 900000, user_id))
        token = 'synthetic-feed-delivery-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}

    def wait_until(condition, label):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(.25)
        raise AssertionError('isolated Feed recovery timed out: ' + label)

    def publish(user_id, title):
        body, _ = request(base, '/blog', 200, 'POST', json.dumps(
            {'title': title, 'content': 'synthetic durable Feed body'}).encode(), headers[user_id])
        return int(json.loads(body)['data'])

    def pending(blog_id, recipient):
        return sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;'
                   % (blog_id, recipient)) == '1'

    for follower in (bad, good):
        request(base, '/follow/%d/true' % author, 200, 'PUT', headers=headers[follower])
    bad_key, good_key = 'feed:%d' % bad, 'feed:%d' % good
    redis('SET', bad_key, 'synthetic wrong-type blocker')
    first = publish(author, 'synthetic accepted despite Feed failure')
    assert sql('SELECT COUNT(*) FROM tb_blog WHERE id=%d AND user_id=%d;' % (first, author)) == '1'
    assert pending(first, bad)
    fixed_score = int(sql('SELECT score_millis FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;'
                          % (first, bad)))
    assert int(float(redis('ZSCORE', good_key, str(first)))) == fixed_score
    wait_until(lambda: sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d '
                           'AND attempts>0 AND next_attempt_at>create_time AND last_error IS NOT NULL;'
                           % (first, bad)) == '1', 'recorded retry')
    reason = sql('SELECT last_error FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;' % (first, bad))
    assert re.fullmatch(r'[A-Za-z0-9_$]{1,64}', reason) and 'synthetic' not in reason
    # The durable audience is the DB follow snapshot, not a later re-query after unfollow.
    request(base, '/follow/%d/false' % author, 200, 'PUT', headers=headers[bad])
    assert pending(first, bad)
    sql((ROOT / 'src/main/resources/db/migration/V011__add_blog_feed_delivery_outbox.sql').read_text('utf-8'))
    assert pending(first, bad)
    redis('DEL', bad_key)  # Only the synthetic blocker created above; no shared Feed data.
    wait_until(lambda: not pending(first, bad) and not pending(first, good), 'fixed-score delivery and ACK')
    assert int(float(redis('ZSCORE', bad_key, str(first)))) == fixed_score
    assert int(float(redis('ZSCORE', good_key, str(first)))) == fixed_score
    assert redis('ZCARD', bad_key) == '1'
    check('Feed outbox: accepted DB post despite one recipient WRONGTYPE, full stable audience/score, safe retry metadata, repeat V011 and recovery after unfollow')

    sql("DELIMITER $$\nCREATE TRIGGER reject_feed_ack BEFORE DELETE ON tb_blog_feed_delivery FOR EACH ROW BEGIN "
        "IF OLD.recipient_user_id=%d THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private ACK fault'; "
        "END IF; END$$\nDELIMITER ;" % good)
    try:
        ack_post = publish(author, 'synthetic Redis-success DB-ACK-failure')
        ack_score = int(sql('SELECT score_millis FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;'
                            % (ack_post, good)))
        before = redis('ZRANGE', good_key, '0', '-1', 'WITHSCORES')
        marker = 'blogId=%d, recipientId=%d, errorClass=' % (ack_post, good)
        wait_until(lambda: marker in compose('--profile', 'app', 'logs', '--no-color', 'app'), 'actual failed ACK attempt')
        assert pending(ack_post, good)
        assert redis('ZRANGE', good_key, '0', '-1', 'WITHSCORES') == before
        request(base, '/blog/%d' % ack_post, 200, 'DELETE', headers=headers[author])
        assert pending(ack_post, good)
    finally:
        sql('DROP TRIGGER IF EXISTS reject_feed_ack;')
    wait_until(lambda: not pending(ack_post, good), 'ACK replay on withdrawn post')
    assert int(float(redis('ZSCORE', good_key, str(ack_post)))) == ack_score
    assert redis('ZCARD', good_key) == '2'
    request(base, '/blog/%d' % ack_post, 404, headers=headers[author])
    check('Feed outbox: Redis-confirmed DB ACK failure retains intent, matching replay does not reorder/duplicate members, withdrawal does not erase pending intent')

    request(base, '/follow/%d/true' % author, 200, 'PUT', headers=headers[bad])
    sql("DELIMITER $$\nCREATE TRIGGER reject_second_feed_intent BEFORE INSERT ON tb_blog_feed_delivery "
        "FOR EACH ROW BEGIN SET @campushub_7m_insert_count=COALESCE(@campushub_7m_insert_count,0)+1; "
        "IF @campushub_7m_insert_count=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private intent fault'; "
        "END IF; END$$\nDELIMITER ;")
    before_count = sql('SELECT COUNT(*) FROM tb_blog;')
    before_bad, before_good = redis('ZRANGE', bad_key, '0', '-1', 'WITHSCORES'), redis('ZRANGE', good_key, '0', '-1', 'WITHSCORES')
    try:
        body, _ = request(base, '/blog', 500, 'POST', json.dumps(
            {'title': 'synthetic must roll back with second intent', 'content': 'synthetic rollback body'}).encode(), headers[author])
        assert json.loads(body)['errorCode'] == 'INTERNAL_ERROR'
        assert b'synthetic private intent fault' not in body and b'SIGNAL' not in body
    finally:
        sql('DROP TRIGGER IF EXISTS reject_second_feed_intent;')
    assert sql('SELECT COUNT(*) FROM tb_blog;') == before_count
    assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id IN (%d,%d);' % (bad, good)) == '0'
    assert redis('ZRANGE', bad_key, '0', '-1', 'WITHSCORES') == before_bad
    assert redis('ZRANGE', good_key, '0', '-1', 'WITHSCORES') == before_good
    check('Feed outbox: later intent INSERT fault rolls back both post and earlier intent, no pre-commit Redis side effects and sanitized HTTP failure')

    request(base, '/follow/%d/true' % restart_author, 200, 'PUT', headers=headers[restart_recipient])
    restart_key = 'feed:%d' % restart_recipient
    redis('SET', restart_key, 'synthetic restart blocker')
    restart_post = publish(restart_author, 'synthetic durable intent survives app restart')
    restart_score = int(sql('SELECT score_millis FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;'
                            % (restart_post, restart_recipient)))
    compose('restart', 'app')
    base = app_base()
    wait_ready(base)
    assert pending(restart_post, restart_recipient)
    redis('DEL', restart_key)
    wait_until(lambda: not pending(restart_post, restart_recipient), 'application restart recovery')
    assert int(float(redis('ZSCORE', restart_key, str(restart_post)))) == restart_score
    body, _ = request(base, '/blog/of/follow?lastId=%d' % (int(time.time() * 1000) + 60000), headers=headers[restart_recipient])
    assert [row['id'] for row in json.loads(body)['data']['list']] == [restart_post]
    check('Feed outbox: pending intent persists across actual app restart and background recovery confirms the original member/score')
    return base


def verify_blog_publication_requests(base):
    """Optional publication keys: only this fresh project's actors, receipts and fault triggers."""
    authors, follower = (900100, 900101, 900102), 900103
    headers = {}
    for user_id, role in zip((*authors, follower), ('USER', 'MERCHANT', 'ADMIN', 'USER')):
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(%d,'00000000%03d','synthetic-request-actor');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(%d,'%s');" % (user_id, user_id - 900000, user_id, role))
        token = 'synthetic-publication-' + uuid.uuid4().hex
        redis('HSET', 'login:token:' + token, 'id', str(user_id), 'nickName', 'synthetic')
        redis('EXPIRE', 'login:token:' + token, '600')
        headers[user_id] = {'authorization': token, 'Content-Type': 'application/json'}
    request(base, '/follow/%d/true' % authors[0], 200, 'PUT', headers=headers[follower])
    feed_key = 'feed:%d' % follower
    redis('SET', feed_key, 'synthetic request retry blocker')
    body = {'title': 'synthetic durable publication request', 'content': 'synthetic campus body'}
    key = 'synthetic-publication-key'

    def publish(actor, payload, request_key=key, expected=200):
        send_headers = dict(headers[actor])
        if request_key is not None:
            send_headers['Idempotency-Key'] = request_key
        raw, _ = request(base, '/blog', expected, 'POST', json.dumps(payload).encode(), send_headers)
        result = json.loads(raw)
        if expected == 200:
            assert result['success'] is True
            return int(result['data'])
        assert not request_key or request_key.encode() not in raw
        return result

    def wait_until(condition, label):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(.25)
        raise AssertionError('isolated publication request timed out: ' + label)

    first_ids = {}
    for actor in authors:
        first_ids[actor] = publish(actor, body)
        for images in (None, '', ' \t '):
            assert publish(actor, dict(body, images=images, id=123, userId=follower, liked=99)) == first_ids[actor]
        assert sql("SELECT COUNT(*) FROM tb_blog_publication_request WHERE user_id=%d AND request_key='%s' "
                   "AND blog_id=%d AND LENGTH(request_hash)=64;" % (actor, key, first_ids[actor])) == '1'
        assert sql('SELECT COUNT(*) FROM tb_blog WHERE user_id=%d;' % actor) == '1'
    assert len(set(first_ids.values())) == 3
    original = first_ids[authors[0]]
    fixed_score = int(sql('SELECT score_millis FROM tb_blog_feed_delivery WHERE blog_id=%d AND recipient_user_id=%d;'
                          % (original, follower)))
    assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE blog_id=%d;' % original) == '1'
    sql((ROOT / 'src/main/resources/db/migration/V012__add_blog_publication_requests.sql').read_text('utf-8'))
    assert publish(authors[0], body) == original
    redis('DEL', feed_key)  # Only the synthetic WRONGTYPE blocker created by this function.
    wait_until(lambda: sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE blog_id=%d;' % original) == '0', 'Feed recovery')
    assert int(float(redis('ZSCORE', feed_key, str(original)))) == fixed_score
    assert redis('ZCARD', feed_key) == '1'
    case_id = publish(authors[0], body, key.upper())
    assert case_id != original
    assert publish(authors[0], body, None) != publish(authors[0], body, None)
    check('Publication keys: all three roles replay the original ID, normalized empty images/ignored fields, author and case isolation, retained V012 receipts and unchanged keyless behavior; Feed faults do not create another intent')

    before_count = sql('SELECT COUNT(*) FROM tb_blog;')
    before_feed = redis('ZRANGE', feed_key, '0', '-1', 'WITHSCORES')
    for changed in (dict(body, title=body['title'] + ' '), dict(body, content='different'),
                    dict(body, images='/synthetic.png'), dict(body, shopId=1, images='/synthetic.png')):
        assert publish(authors[0], changed, expected=409)['errorCode'] == 'CONFLICT'
    # Transport may normalize HTTP outer whitespace; do not claim byte-level header validation.
    for invalid in ('', 'a b', 'a,b', 'x' * 65):
        assert publish(authors[0], body, invalid, 400)['errorCode'] == 'VALIDATION_FAILED'
    request(base, '/blog', 401, 'POST', json.dumps(body).encode(), {'Content-Type': 'application/json', 'Idempotency-Key': key})
    assert sql('SELECT COUNT(*) FROM tb_blog;') == before_count
    assert redis('ZRANGE', feed_key, '0', '-1', 'WITHSCORES') == before_feed
    check('Publication key boundaries: changed effective content conflicts, invalid key syntax rejects, anonymous replay is unauthorized, no extra rows or Feed mutations')

    barrier = threading.Barrier(4)
    def concurrent_retry(_):
        barrier.wait(timeout=10)
        return publish(authors[0], body, 'synthetic-parallel-key')
    before_count = int(sql('SELECT COUNT(*) FROM tb_blog;'))
    with ThreadPoolExecutor(max_workers=4) as pool:
        parallel_ids = list(pool.map(concurrent_retry, range(4)))
    assert len(set(parallel_ids)) == 1
    assert int(sql('SELECT COUNT(*) FROM tb_blog;')) == before_count + 1
    assert sql("SELECT COUNT(*) FROM tb_blog_publication_request WHERE user_id=%d AND request_key='synthetic-parallel-key' "
               "AND blog_id=%d;" % (authors[0], parallel_ids[0])) == '1'
    assert redis('ZSCORE', feed_key, str(parallel_ids[0])) != ''
    check('Publication key race: four simultaneous real HTTP requests commit exactly one synthetic post/receipt and return the same ID; not a capacity measurement')

    wait_until(lambda: sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id=%d;' % follower) == '0', 'settled fault baseline')
    for trigger, event, target, condition in (
            ('reject_publication_receipt', 'UPDATE', 'tb_blog_publication_request',
             "NEW.user_id=%d AND NEW.request_key='synthetic-rollback-key'" % authors[0]),
            ('reject_publication_intent', 'INSERT', 'tb_blog_feed_delivery', 'NEW.recipient_user_id=%d' % follower)):
        fault_key = 'synthetic-rollback-key' if event == 'UPDATE' else 'synthetic-intent-rollback-key'
        before_count = sql('SELECT COUNT(*) FROM tb_blog;')
        before_feed = redis('ZRANGE', feed_key, '0', '-1', 'WITHSCORES')
        sql("DELIMITER $$\nCREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW BEGIN IF %s THEN "
            "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private publication fault'; END IF; END$$\nDELIMITER ;"
            % (trigger, event, target, condition))
        try:
            result = publish(authors[0], body, fault_key, 500)
            assert result['errorCode'] == 'INTERNAL_ERROR' and 'synthetic private' not in result.get('errorMsg', '')
        finally:
            sql('DROP TRIGGER IF EXISTS %s;' % trigger)
        assert sql('SELECT COUNT(*) FROM tb_blog;') == before_count
        assert sql("SELECT COUNT(*) FROM tb_blog_publication_request WHERE user_id=%d AND request_key='%s';"
                   % (authors[0], fault_key)) == '0'
        assert sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id=%d;' % follower) == '0'
        assert redis('ZRANGE', feed_key, '0', '-1', 'WITHSCORES') == before_feed
        recovered_id = publish(authors[0], body, fault_key)
        assert publish(authors[0], body, fault_key) == recovered_id
        wait_until(lambda: sql('SELECT COUNT(*) FROM tb_blog_feed_delivery WHERE recipient_user_id=%d;' % follower) == '0', 'fault retry ACK')
    check('Publication receipt/intent SQL faults: post, reservation and Feed intent roll back together, no pre-commit Feed writes, sanitized 500 and same-key retry succeeds')

    request(base, '/blog/%d' % original, 200, 'PUT', json.dumps(dict(body, title='synthetic edited')).encode(), headers[authors[0]])
    assert publish(authors[0], body) == original
    assert sql('SELECT title FROM tb_blog WHERE id=%d;' % original) == 'synthetic edited'
    request(base, '/blog/%d' % original, 200, 'DELETE', headers=headers[authors[0]])
    compose('restart', 'app')
    base = app_base()
    wait_ready(base)
    assert publish(authors[0], body) == original
    request(base, '/blog/%d' % original, 404, headers=headers[authors[0]])
    assert sql('SELECT withdrawn FROM tb_blog WHERE id=%d;' % original) == '1'
    assert int(float(redis('ZSCORE', feed_key, str(original)))) == fixed_score
    sql("UPDATE tb_user SET status='DISABLED' WHERE id=%d;" % authors[0])
    try:
        publish(authors[0], body, expected=401)
    finally:
        sql("UPDATE tb_user SET status='ACTIVE' WHERE id=%d;" % authors[0])
    check('Publication receipt survives app restart: replay after edit/withdrawal returns only the original ID without restoring content or refreshing Feed score; current disabled identity still rejects')
    return base


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
        assert sql("SELECT CONCAT(IS_NULLABLE,':',COLUMN_DEFAULT) FROM INFORMATION_SCHEMA.COLUMNS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog' AND COLUMN_NAME='withdrawn';") == 'NO:0'
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_feed_delivery' AND INDEX_NAME='PRIMARY';") == 'blog_id,recipient_user_id'
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_publication_request' AND INDEX_NAME='PRIMARY';") == 'user_id,request_key'
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_reply_hide_audit' "
                   "AND INDEX_NAME='uk_blog_reply_hide_audit_reply' AND NON_UNIQUE=0;") == 'reply_id'
        assert sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS "
                   "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_reply_hide_audit' "
                   "AND INDEX_NAME='idx_blog_reply_hide_audit_page';") == 'blog_id,id'
        check("V001-V014 repeat, nullable post store, withdrawn flag, Feed delivery/receipt tables, root/reply hide audit page indexes, populated-database bootstrap refusal")
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900001,'00000000001','synthetic-user'),(900002,'00000000002','synthetic-admin');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(900001,'USER'),(900002,'ADMIN');")
        tokens = ["synthetic-phase5-" + uuid.uuid4().hex for _ in range(2)]
        for user, token in zip((900001, 900002), tokens):
            redis("HSET", "login:token:" + token, "id", str(user), "nickName", "synthetic")
            redis("EXPIRE", "login:token:" + token, "600")
        verify_follow_command_module(base)
        verify_follow_query_module(base)
        verify_shop_type_module(base, tokens)
        verify_check_in_module(base)
        verify_campus_posts(base, tokens)
        verify_text_only_campus_posts(base, tokens)
        verify_first_level_comments(base, tokens)
        verify_direct_comment_replies(base, tokens)
        verify_reply_withdrawal(base, tokens)
        verify_first_level_withdrawal(base, tokens)
        verify_first_level_moderation(base, tokens)
        verify_direct_reply_moderation(base)
        verify_reply_hide_audit_query(base)
        verify_reply_hide_audit_pages(base)
        verify_comment_hide_audit_query(base, tokens)
        verify_comment_hide_audit_pages(base, tokens)
        verify_post_author_edit(base, tokens)
        verify_post_author_withdrawal(base, tokens)
        verify_follow_feed_cursor(base)
        base = verify_blog_feed_delivery(base)
        base = verify_blog_publication_requests(base)
        # Existing campus-post scenarios assert the fresh database starts with zero posts.
        verify_follower_reader_publication(base)
        verify_voucher_query_module(base)
        verify_user_profile_module(base)
        verify_blog_user_reader(base)
        verify_session_logout_module(base)
        verify_login_code_module(base)
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
