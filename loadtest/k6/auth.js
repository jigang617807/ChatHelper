import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL, duration, vus } from './lib/config.js';
import { login } from './lib/session.js';

export const options = {
  vus: vus(5),
  duration: duration('1m'),
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

const pageSuccess = new Rate('page_success');
const homePageMs = new Trend('home_page_ms', true);
const documentListMs = new Trend('document_list_ms', true);

export default function () {
  login();

  const home = http.get(`${BASE_URL}/auth/home`);
  homePageMs.add(home.timings.duration);
  const homeOk = check(home, {
    'home status is 200': (r) => r.status === 200,
    'home is not login page': (r) => !String(r.url || '').includes('/auth/login'),
  });

  const docs = http.get(`${BASE_URL}/doc/list`);
  documentListMs.add(docs.timings.duration);
  const docsOk = check(docs, {
    'doc list status is 200': (r) => r.status === 200,
    'doc list is not login page': (r) => !String(r.url || '').includes('/auth/login'),
  });

  pageSuccess.add(homeOk && docsOk);

  sleep(1);
}
