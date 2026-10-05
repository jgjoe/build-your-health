import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://app:8081';
const tps = Number(__ENV.TPS || 20);
const seconds = Number(__ENV.SECONDS || 120);
const vus = Number(__ENV.VUS || 256);
if (!Number.isInteger(tps) || tps < 20 || tps > 20480 || !Number.isInteger(vus) || vus < 40 || seconds < 120) throw new Error('Invalid load parameters');
const latency = new Trend('measured_latency', true);
const requests = new Counter('measured_requests');
const started = new Counter('measured_started');
const errors = new Rate('measured_errors');
const created = new Counter('created_orders');
const kinds = ['products', 'history', 'login', 'create'];
const thresholdMap = {
  measured_latency: ['p(95)<500'],
  measured_errors: ['rate<0.01'],
  dropped_iterations: ['count==0'],
};
for (const kind of kinds) thresholdMap[`measured_latency{kind:${kind}}`] = ['p(95)<500'];
export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', rate: tps, timeUnit: '1s', duration: '30s', preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '5s' },
    measured: { executor: 'constant-arrival-rate', rate: tps, timeUnit: '1s', startTime: '40s', duration: `${seconds}s`, preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '5s' },
  },
  thresholds: thresholdMap,
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
  noCookiesReset: false,
  setupTimeout: '60s',
};

export function setup() {
  const sessions = [];
  for (let i = 0; i < 100; i++) {
    const sequence = i === 0 ? 1 : i < 10 ? 20 + i : 2011 + i;
    const id = `u${String(sequence).padStart(6, '0')}`;
    // One-shot session cookies are passed explicitly to authenticated requests.
    // Login workload does not reuse them, since the app rotates session IDs.
    http.cookieJar().clear(base);
    const res = http.post(`${base}/api/auth/login`, JSON.stringify({ id, password: 'demo1234' }),
      { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'setup', kind: 'login' }, timeout: '5s' });
    if (res.status !== 200 || !res.cookies.JSESSIONID) throw new Error(`Setup login failed for ${id}: ${res.status}`);
    sessions.push({ id, tier: i === 0 ? 'heavy' : i < 10 ? 'mid' : 'light', cookie: res.cookies.JSESSIONID[0].value });
  }
  return sessions;
}

export default function (sessions) {
  const index = exec.scenario.iterationInTest;
  const slot = index % 20;
  const kind = slot < 14 ? 'products' : slot < 17 ? 'history' : slot < 19 ? 'login' : 'create';
  // Cycle accounts independently of the 20-slot operation mix.
  const session = sessions[(Math.floor(index / 20) * 17 + slot * 7) % sessions.length];
  const phase = exec.scenario.name;
  const params = { headers: { 'Content-Type': 'application/json' }, tags: { phase, kind }, timeout: '5s' };
  if (phase === 'measured') started.add(1, { kind });
  let res;
  if (kind === 'products') {
    const page = 1 + Math.floor(index / 20) % 20;
    const search = slot % 7 === 0 ? '&field=name&keyword=perf' : '';
    res = http.get(`${base}/api/products?page=${page}&size=10${search}`, params);
  } else if (kind === 'login') {
    http.cookieJar().clear(base);
    res = http.post(`${base}/api/auth/login`, JSON.stringify({ id: session.id, password: 'demo1234' }), params);
  } else {
    http.cookieJar().clear(base);
    params.headers.Cookie = `JSESSIONID=${session.cookie}`;
    if (kind === 'history') {
      const page = session.tier === 'light' ? 1 : 1 + Math.floor(index / 20) % 3;
      res = http.get(`${base}/api/orders?page=${page}&size=10`, params);
    } else {
      const date = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10);
      res = http.post(`${base}/api/orders`, JSON.stringify({
        recipientName: 'K6_LOAD_TEST', deliveryDate: date, address: 'Local synthetic load test', zipcode: '00000',
        items: [{ productId: 'X00001', quantity: 1 }, { productId: 'X00002', quantity: 2 }],
      }), params);
    }
  }
  let valid = res.status === (kind === 'create' ? 201 : 200);
  try {
    const body = res.json();
    if (kind === 'create') valid = valid && body.orderId > 600000 && body.totalPrice > 0;
    else if (kind === 'login') valid = valid && body.id === session.id;
    else valid = valid && Array.isArray(body.items) && body.items.length > 0 && body.total > 0;
  } catch (_) { valid = false; }
  check(res, { 'status and response contract': () => valid }, { phase, kind });
  if (phase === 'measured') {
    latency.add(res.timings.duration, { kind });
    requests.add(1, { kind });
    errors.add(!valid, { kind });
    if (kind === 'create' && valid) created.add(1);
  }
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify({ tps, seconds, vus, ...data }, null, 2) };
}
