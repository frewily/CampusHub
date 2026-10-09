import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

// Only the harness-discovered, test-owned localhost port is configurable, never the host.
const TARGET_PORT = __ENV.TARGET_PORT;
if (typeof TARGET_PORT !== "string" || !/^[1-9][0-9]{0,4}$/.test(TARGET_PORT) || Number(TARGET_PORT) > 65535) {
  throw new Error("TARGET_PORT must be a numeric localhost port discovered by the isolated harness");
}
const BASE_URL = `http://127.0.0.1:${TARGET_PORT}`;
const DETAIL_URL = `${BASE_URL}/shop/10001`;
const SEARCH_URL = `${BASE_URL}/shop/search?keyword=synthetic-baseline&sort=price_asc&page=1&size=10`;
const CASE = __ENV.CASE === undefined ? "shop_detail_warm" : __ENV.CASE;
const VUS = __ENV.VUS === undefined ? "1" : __ENV.VUS;
const DURATION = __ENV.DURATION === undefined ? "5s" : __ENV.DURATION;

if (__ENV.BASE_URL !== undefined) {
  throw new Error("BASE_URL is not configurable; the target host is fixed to 127.0.0.1");
}
if (CASE !== "shop_detail_warm" && CASE !== "shop_search_mysql") {
  throw new Error("CASE must be shop_detail_warm or shop_search_mysql");
}
if (VUS !== "1" && VUS !== "10") {
  throw new Error("VUS must be 1 or 10");
}
if (DURATION !== "5s" && DURATION !== "20s") {
  throw new Error("DURATION must be 5s or 20s");
}

const businessErrors = new Counter("business_errors");

export const options = {
  maxRedirects: 0,
  scenarios: {
    baseline: {
      executor: "constant-vus",
      vus: Number(VUS),
      duration: DURATION,
      gracefulStop: "5s",
      exec: "runBaseline",
    },
  },
  tags: { case: CASE },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate==0"],
    business_errors: ["count==0"],
  },
};

export function runBaseline() {
  let failed = false;

  try {
    if (CASE === "shop_detail_warm") {
      const response = http.get(DETAIL_URL, {
        timeout: "5s",
        tags: { name: "shop_detail", case: CASE },
      });
      let body = null;
      try {
        body = JSON.parse(response.body);
      } catch (_) {
        // Invalid or empty JSON is reported by the checks and fixed counter below.
      }

      failed = !check(response, {
        "HTTP 200": (r) => r.status === 200,
        "Result.success true": () => body !== null && body.success === true,
        "shop id is 10001": () => body !== null && body.data !== null && typeof body.data === "object" && body.data.id === 10001,
        "shop name matches fixture": () => body !== null && body.data !== null && typeof body.data === "object" && body.data.name === "synthetic-baseline-0001",
      });
    } else {
      const response = http.get(SEARCH_URL, {
        timeout: "5s",
        tags: { name: "shop_search", case: CASE },
      });
      let body = null;
      try {
        body = JSON.parse(response.body);
      } catch (_) {
        // Invalid or empty JSON is reported by the checks and fixed counter below.
      }

      const data = body !== null && body.data !== null && typeof body.data === "object" ? body.data : null;
      const items = data !== null && Array.isArray(data.items) ? data.items : [];
      failed = !check(response, {
        "HTTP 200": (r) => r.status === 200,
        "Result.success true": () => body !== null && body.success === true,
        "search total is 1000": () => data !== null && data.total === 1000,
        "search returns 10 items": () => items.length === 10,
        "search item names match fixture": () =>
          items.length === 10 && items.every((item) =>
            item !== null && typeof item.name === "string" && item.name.startsWith("synthetic-baseline-")),
        "request uses price_asc": () => SEARCH_URL.includes("sort=price_asc"),
        "response sort is price_asc": () => data !== null && data.sort === "price_asc",
      });
    }
  } catch (_) {
    // Keep a single unexpected iteration error visible in the fixed counter; do not abort the run.
    failed = true;
  }

  // Emit a zero sample on successful iterations so the exported summary includes the counter.
  businessErrors.add(failed ? 1 : 0, { name: "business_errors", case: CASE });
}

// This is a closed-loop constant-VU baseline with no think time. It is not an
// open-arrival-rate workload, a peak-capacity test, or a model of real users.
// Keep k6's default connection reuse enabled; 5s and 20s are separate runs.
