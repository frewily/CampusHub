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
        assert response.status == expected, "unexpected HTTP status for " + route.split("?")[0]
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
        check("V001-V005 repeat and populated-database bootstrap refusal")
        sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(900001,'00000000001','synthetic-user'),(900002,'00000000002','synthetic-admin');"
            "INSERT INTO tb_user_role(user_id,role) VALUES(900001,'USER'),(900002,'ADMIN');")
        tokens = ["synthetic-phase5-" + uuid.uuid4().hex for _ in range(2)]
        for user, token in zip((900001, 900002), tokens):
            redis("HSET", "login:token:" + token, "id", str(user), "nickName", "synthetic")
            redis("EXPIRE", "login:token:" + token, "600")
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
