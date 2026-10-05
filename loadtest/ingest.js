// k6 load test for the ingest path: POST /messages -> one transaction that
// writes the message and fans it out into delivery rows (the outbox).
//
// Run against an app started with relay.dispatch.enabled=false and a
// throwaway database - see docs/benchmarks.md. Nothing is sent to any endpoint.
//
//   docker run --rm --network host -v "$PWD/loadtest:/scripts" grafana/k6 \
//       run -e BASE=http://localhost:8089 -e RATE=500 /scripts/ingest.js

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 500);          // target requests per second
const DURATION = __ENV.DURATION || '60s';
const EVENT_TYPES = ['invoice.paid', 'invoice.created', 'customer.updated'];

export const options = {
  scenarios: {
    // Open model: requests arrive at a fixed rate whether or not earlier ones
    // finished. A closed model (fixed VUs) slows its own arrival rate when the
    // server slows down, which hides exactly the latency we want to see.
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:ingest}': ['p(99)<250'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function post(path, body, tags) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    tags,
  });
}

// One application with three endpoints, each subscribed differently, so every
// message fans out to 1-3 delivery rows like real traffic would.
export function setup() {
  const app = post('/api/v1/applications', { name: 'k6-load' }).json();
  const endpoints = [
    { url: 'https://example.com/all', eventTypes: [] },
    { url: 'https://example.com/invoices', eventTypes: ['invoice.paid', 'invoice.created'] },
    { url: 'https://example.com/paid', eventTypes: ['invoice.paid'] },
  ];
  for (const e of endpoints) {
    const res = post(`/api/v1/applications/${app.id}/endpoints`, e);
    if (res.status !== 201 && res.status !== 200) {
      throw new Error(`endpoint create failed: ${res.status} ${res.body}`);
    }
  }
  return { appId: app.id };
}

export default function (data) {
  const eventType = EVENT_TYPES[Math.floor(Math.random() * EVENT_TYPES.length)];
  const res = post(`/api/v1/applications/${data.appId}/messages`, {
    eventType,
    payload: {
      id: `evt_${__VU}_${__ITER}`,
      amount: Math.floor(Math.random() * 100000),
      currency: 'INR',
      customer: { id: `cus_${__VU}`, email: `user${__VU}@example.com` },
    },
  }, { name: 'ingest' });

  check(res, { '202 Accepted': (r) => r.status === 202 });
}
