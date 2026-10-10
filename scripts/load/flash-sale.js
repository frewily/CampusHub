import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

const TARGET_PORT = __ENV.TARGET_PORT;
const ACTOR_COUNT = __ENV.ACTORS;
const FIXTURE_PATH = __ENV.FIXTURE_PATH;

if (typeof TARGET_PORT !== "string" || !/^[1-9][0-9]{0,4}$/.test(TARGET_PORT)) {
  throw new Error("TARGET_PORT must be a numeric localhost port");
}
const portNumber = Number(TARGET_PORT);
if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
  throw new Error("TARGET_PORT must be between 1 and 65535");
}
if (__ENV.BASE_URL !== undefined) {
  throw new Error("BASE_URL is not configurable; the target host is fixed to 127.0.0.1");
}
if (ACTOR_COUNT !== "10" && ACTOR_COUNT !== "20" && ACTOR_COUNT !== "200") {
  throw new Error("ACTORS must be 10, 20, or 200");
}
if (typeof FIXTURE_PATH !== "string" || FIXTURE_PATH[0] !== "/" ||
    FIXTURE_PATH.slice(FIXTURE_PATH.lastIndexOf("/") + 1) !== "campushub-flash-sale-fixture.json") {
  throw new Error("FIXTURE_PATH must be an absolute path to campushub-flash-sale-fixture.json");
}

function isPositiveLongDecimal(value) {
  if (typeof value !== "string" || !/^[1-9]\d*$/.test(value)) return false;
  const maxLong = "9223372036854775807";
  return value.length < maxLong.length ||
    (value.length === maxLong.length && value <= maxLong);
}

function hasExactKeys(value, expectedKeys) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const actualKeys = Object.keys(value);
  return actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => actualKeys.indexOf(key) !== -1);
}

let fixture;
try {
  fixture = JSON.parse(open(FIXTURE_PATH));
} catch (_) {
  throw new Error("FIXTURE_PATH does not contain valid fixture JSON");
}

if (!hasExactKeys(fixture, ["version", "activityId", "actors"]) ||
    typeof fixture.version !== "number" || !Number.isInteger(fixture.version) || fixture.version !== 1 ||
    !isPositiveLongDecimal(fixture.activityId) || !Array.isArray(fixture.actors) ||
    fixture.actors.length !== Number(ACTOR_COUNT)) {
  throw new Error("Fixture structure, version, activityId, or actor count is invalid");
}

const seenActorIds = {};
const seenTokens = {};
for (let i = 0; i < fixture.actors.length; i += 1) {
  const actor = fixture.actors[i];
  if (!hasExactKeys(actor, ["id", "token"]) || !isPositiveLongDecimal(actor.id) ||
      typeof actor.token !== "string" || !/^[0-9a-f]{32}$/.test(actor.token) ||
      seenActorIds[actor.id] === true || seenTokens[actor.token] === true) {
    throw new Error("Fixture actors must have unique positive IDs and unique lowercase tokens");
  }
  seenActorIds[actor.id] = true;
  seenTokens[actor.token] = true;
}

const BASE_URL = `http://127.0.0.1:${portNumber}`;
const ACTIVITY_ID = fixture.activityId;
const ACTORS = fixture.actors;

const admissionNew = new Counter("admission_new");
const admissionReplayed = new Counter("admission_replayed");
const admissionSoldOut = new Counter("admission_sold_out");
const unexpectedOutcomes = new Counter("unexpected_outcomes");
const admissionNewMs = new Trend("admission_new_ms");
const admissionReplayedMs = new Trend("admission_replayed_ms");
const admissionSoldOutMs = new Trend("admission_sold_out_ms");

http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  maxRedirects: 0,
  // Drop automatic URL/status tags so activity identifiers and response details
  // cannot become metric labels. The only HTTP request tag below is constant.
  systemTags: [],
  scenarios: {
    flash_sale: {
      executor: "per-vu-iterations",
      vus: Number(ACTOR_COUNT),
      iterations: 1,
      maxDuration: "30s",
      gracefulStop: "5s",
      exec: "runFlashSale",
    },
  },
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate==0"],
    unexpected_outcomes: ["count==0"],
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
};

function parseBody(response) {
  if (response === null || typeof response.body !== "string") return null;
  try {
    return JSON.parse(response.body);
  } catch (_) {
    return null;
  }
}

function isSoldOut(response, body) {
  return response !== null && response.status === 409 && body !== null &&
    body.success === false && body.errorCode === "SOLD_OUT";
}

function hasLegacyIdProjection(body) {
  // Check the legacy numeric field without treating it as a lossless 64-bit identity.
  return typeof body.data === "number" && Number.isFinite(body.data) &&
    Number.isInteger(body.data) && body.data > 0 && body.data === Number(body.orderId);
}

function isNewAdmission(response, body) {
  return response !== null && response.status === 200 && body !== null &&
    body.success === true && body.acceptanceStatus === "ACCEPTED" &&
    body.replayed === false && isPositiveLongDecimal(body.orderId) && hasLegacyIdProjection(body);
}

function isReplay(response, body, initialOrderId) {
  return response !== null && response.status === 200 && body !== null &&
    body.success === true && body.acceptanceStatus === "ACCEPTED" &&
    body.replayed === true && isPositiveLongDecimal(body.orderId) && hasLegacyIdProjection(body) &&
    typeof initialOrderId === "string" && body.orderId === initialOrderId;
}

function sendAdmission(actor) {
  try {
    return {
      response: http.post(`${BASE_URL}/voucher-order/seckill/${ACTIVITY_ID}`, null, {
        headers: { authorization: actor.token },
        timeout: "5s",
        tags: { name: "flash_sale_admission" },
      }),
      failed: false,
    };
  } catch (_) {
    // Return a safe sentinel so the caller still sends this actor's second request.
    return { response: null, failed: true };
  }
}

function recordRequest(response, requestFailed, expectedOutcome, initialOrderId, isInitial) {
  const body = parseBody(response);
  const soldOut = isSoldOut(response, body);
  const newAdmission = isNewAdmission(response, body);
  const replayed = !isInitial && isReplay(response, body, initialOrderId);
  const expectedStatus = response !== null &&
    (response.status === 200 || soldOut);
  const contractValid = isInitial
    ? (newAdmission || soldOut)
    : (expectedOutcome === "new" ? replayed : expectedOutcome === "sold_out" && soldOut);
  const replayMatches = isInitial ||
    (expectedOutcome === "new" ? replayed : expectedOutcome === "sold_out" && soldOut);

  // Keep all three independent checks on every response, including exceptions.
  check(response, { "expected response status": () => expectedStatus && !requestFailed });
  check(response, { "response contract": () => contractValid && !requestFailed });
  check(response, { "replay matches initial outcome": () => replayMatches });

  const validOutcome = expectedStatus && contractValid && replayMatches && !requestFailed;
  const countNew = validOutcome && isInitial && newAdmission;
  const countReplayed = validOutcome && !isInitial && replayed;
  const countSoldOut = validOutcome && soldOut;
  admissionNew.add(countNew ? 1 : 0);
  admissionReplayed.add(countReplayed ? 1 : 0);
  admissionSoldOut.add(countSoldOut ? 1 : 0);
  unexpectedOutcomes.add(validOutcome ? 0 : 1);

  if (validOutcome && response.timings !== null && typeof response.timings === "object" &&
      typeof response.timings.duration === "number" &&
      Number.isFinite(response.timings.duration) && response.timings.duration >= 0) {
    if (countNew) admissionNewMs.add(response.timings.duration);
    if (countReplayed) admissionReplayedMs.add(response.timings.duration);
    if (countSoldOut) admissionSoldOutMs.add(response.timings.duration);
  }

  return isInitial && validOutcome
    ? { outcome: newAdmission ? "new" : "sold_out", orderId: newAdmission ? body.orderId : null }
    : { outcome: null, orderId: null };
}

export function runFlashSale() {
  const actor = ACTORS[__VU - 1];
  const firstRequest = sendAdmission(actor);
  const first = recordRequest(firstRequest.response, firstRequest.failed, null, null, true);

  // Always issue the second request, even when the first request threw or failed checks.
  const secondRequest = sendAdmission(actor);
  recordRequest(secondRequest.response, secondRequest.failed, first.outcome, first.orderId, false);

  const witnessOutcome = first.outcome === "new"
    ? "accepted"
    : first.outcome === "sold_out" ? "sold_out" : "unexpected";
  console.log("COHORT_WITNESS " + JSON.stringify({
    userId: actor.id,
    orderId: witnessOutcome === "accepted" ? first.orderId : null,
    outcome: witnessOutcome,
  }));
}

// This is one finite, closed-loop batch: each actor submits twice against one
// activity. It does not model synchronized starts, steady state, or peak capacity.
