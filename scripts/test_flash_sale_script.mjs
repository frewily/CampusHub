// Test the unchanged k6 module logic in a local VM with HTTP/metrics stubs.
// This is NOT native k6, network acceptance, or a load measurement.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./load/flash-sale.js', import.meta.url), 'utf8')
  .replace(/^import .*;\s*$/gm, '').replace(/^export /gm, '');
const actors = Array.from({ length: 20 }, (_, i) => ({ id: String(i + 1), token: (i + 1).toString(16).padStart(32, '0') }));
const fixture = { version: 1, activityId: '123', actors };
const accepted = (id = '9223372036854775807', replayed = false) => ({ status: 200, body: JSON.stringify({
  success: true, acceptanceStatus: 'ACCEPTED', replayed, orderId: id, data: Number(id),
}), timings: { duration: 2 } });
const sold = () => ({ status: 409, body: JSON.stringify({ success: false, errorCode: 'SOLD_OUT' }), timings: { duration: 3 } });
let total = 0;

function execute(responses, overrides = {}, data = fixture, run = true) {
  const calls = [], checks = [], logs = [], counters = {}, trends = {};
  const context = {
    __ENV: { TARGET_PORT: '12345', ACTORS: '20', FIXTURE_PATH: '/synthetic/campushub-flash-sale-fixture.json', ...overrides },
    __VU: 1, open: () => JSON.stringify(data),
    http: {
      expectedStatuses: (...statuses) => statuses,
      setResponseCallback: (statuses) => assert.deepEqual(statuses, [200, 409]),
      post: (url, body, options) => {
        calls.push({ url, body, options });
        const response = responses[calls.length - 1];
        if (response instanceof Error) throw response;
        return response;
      },
    },
    check: (response, tests) => {
      const result = Object.values(tests).map((test) => test(response));
      checks.push(...result);
      return result.every(Boolean);
    },
    Counter: class { constructor(name) { this.name = name; counters[name] = []; } add(value) { counters[this.name].push(value); } },
    Trend: class { constructor(name) { this.name = name; trends[name] = []; } add(value) { trends[this.name].push(value); } },
    console: { log: (line) => logs.push(line) },
  };
  vm.runInNewContext(source + (run ? '\nrunFlashSale();' : ''), context, { timeout: 1000 });
  return { calls, checks, logs, counters, trends };
}

let result = execute([accepted(), accepted('9223372036854775807', true)]);
assert.equal(result.calls.length, 2);
assert.equal(result.checks.length, 6);
assert.ok(result.checks.every(Boolean));
assert.deepEqual(result.counters.admission_new, [1, 0]);
assert.deepEqual(result.counters.admission_replayed, [0, 1]);
assert.deepEqual(result.counters.unexpected_outcomes, [0, 0]);
for (const call of result.calls) {
  assert.equal(call.options.headers.authorization, actors[0].token);
  assert.equal(call.url, 'http://127.0.0.1:12345/voucher-order/seckill/123');
  assert.equal(call.options.timeout, '5s');
  assert.deepEqual(Object.keys(call.options.tags), ['name']);
}
assert.equal(result.logs.length, 1);
assert.ok(!result.logs[0].includes(actors[0].token));
assert.equal(JSON.parse(result.logs[0].slice('COHORT_WITNESS '.length)).orderId, '9223372036854775807');
total++;

result = execute([sold(), sold()]);
assert.ok(result.checks.every(Boolean));
assert.deepEqual(result.counters.admission_sold_out, [1, 1]);
total++;

for (const responses of [
  [accepted('101'), accepted('102', true)],
  [accepted('101'), accepted('101', false)],
  [sold(), { ...sold(), body: '{"success":false,"errorCode":"CONFLICT"}' }],
  [new Error('synthetic transport error'), sold()],
  [accepted('101'), new Error('synthetic second error')],
  [accepted('9223372036854775808'), accepted('9223372036854775808', true)],
  [{ ...sold(), status: 503 }, sold()],
  [{ ...sold(), body: 'not json' }, sold()],
  [accepted('101'), { ...accepted('101', true), body: JSON.stringify({ success: true,
    acceptanceStatus: 'ACCEPTED', replayed: true, orderId: '101' }) }],
  [accepted('101'), { ...accepted('101', true), body: JSON.stringify({ success: true,
    acceptanceStatus: 'ACCEPTED', replayed: true, orderId: '101', data: '101' }) }],
  [accepted('101'), { ...accepted('101', true), body: JSON.stringify({ success: true,
    acceptanceStatus: 'ACCEPTED', replayed: true, orderId: '101', data: 999 }) }],
]) {
  result = execute(responses);
  assert.equal(result.calls.length, 2);
  assert.equal(result.checks.length, 6);
  assert.ok(result.checks.some((value) => !value));
  assert.ok(result.counters.unexpected_outcomes.some((value) => value === 1));
  total++;
}

for (const overrides of [
  { TARGET_PORT: '0' }, { TARGET_PORT: '01' }, { TARGET_PORT: '65536' },
  { TARGET_PORT: 'http://example.com' }, { TARGET_PORT: '-1' },
  { BASE_URL: 'http://example.com' }, { ACTORS: '1' }, { ACTORS: '201' },
  { FIXTURE_PATH: 'relative/campushub-flash-sale-fixture.json' }, { FIXTURE_PATH: '/synthetic/wrong.json' },
]) {
  assert.throws(() => execute([], overrides, fixture, false));
  total++;
}
for (const mutate of [
  (data) => { data.version = true; },
  (data) => { data.activityId = '9223372036854775808'; },
  (data) => { data.actors[1].id = data.actors[0].id; },
  (data) => { data.actors[1].token = data.actors[0].token; },
  (data) => { data.actors.pop(); },
  (data) => { data.extra = 'synthetic'; },
]) {
  const data = structuredClone(fixture); mutate(data);
  assert.throws(() => execute([], {}, data, false));
  total++;
}
console.log(`PASS: ${total} local VM script cases; no actual HTTP or k6 execution`);
