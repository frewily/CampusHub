#!/bin/sh
# Read-only checks. Does not login, publish inventory, mutate rows or print credentials.
set -eu
base=${1:-http://127.0.0.1:8081}
base=${base%/}
case "$base" in http://*|https://*) ;; *) echo 'Expected an HTTP(S) base URL' >&2; exit 1 ;; esac
curl --fail --silent --show-error --max-time 10 "$base/health/live" | python3 -c '
import json,sys
assert json.load(sys.stdin).get("status") == "UP", "liveness is not UP"
'
curl --fail --silent --show-error --max-time 10 "$base/health/ready" | python3 -c '
import json,sys
assert json.load(sys.stdin).get("status") == "UP", "readiness is not UP"
'
curl --fail --silent --show-error --max-time 10 "$base/shop/search?page=1&size=1" | python3 -c '
import json,sys
r=json.load(sys.stdin)
assert r.get("success") is True, "search failed"
d=r["data"]
assert d["total"] >= 1 and len(d["items"]) == 1, "seeded shop missing"
assert isinstance(d["items"][0]["id"],str), "shop id must be a string"
'
echo 'PASS: liveness, readiness and seeded shop search'
