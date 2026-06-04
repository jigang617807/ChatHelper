import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, duration, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

export const options = {
  vus: vus(5),
  duration: duration('1m'),
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  ensureLoggedIn();

  const home = http.get(`${BASE_URL}/auth/home`);
  check(home, {
    'home status is 200': (r) => r.status === 200,
  });

  const docs = http.get(`${BASE_URL}/doc/list`);
  check(docs, {
    'doc list status is 200': (r) => r.status === 200,
  });

  sleep(1);
}

