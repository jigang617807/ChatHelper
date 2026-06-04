import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, duration, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

const question = __ENV.QUESTION || 'Explain RAG in three sentences.';

export const options = {
  vus: vus(1),
  duration: duration('1m'),
  thresholds: {
    http_req_failed: ['rate<0.1'],
    http_req_duration: ['p(95)<120000'],
    sse_done_count: ['count>0'],
  },
};

const sseDoneCount = new Counter('sse_done_count');
const sseTotalMs = new Trend('sse_total_ms');
const sseFirstByteMs = new Trend('sse_first_byte_ms');

function extractAgentSessionId(html) {
  const match = html.match(/id="agentSessionId"\s+value="([^"]+)"/);
  return match ? match[1] : null;
}

export default function () {
  ensureLoggedIn();

  const page = http.get(`${BASE_URL}/agent`);
  const sessionId = extractAgentSessionId(page.body || '');
  check(page, {
    'agent page status is 200': (r) => r.status === 200,
    'agent session id exists': () => Boolean(sessionId),
  });

  if (!sessionId) {
    sleep(1);
    return;
  }

  const begin = Date.now();
  const response = http.get(
    `${BASE_URL}/agent/ask?sessionId=${encodeURIComponent(sessionId)}&question=${encodeURIComponent(question)}`,
    {
      timeout: '240s',
      headers: {
        Accept: 'text/event-stream',
      },
    }
  );
  const body = response.body || '';

  sseTotalMs.add(Date.now() - begin);
  sseFirstByteMs.add(response.timings.waiting);

  const ok = check(response, {
    'agent sse status is 200': (r) => r.status === 200,
    'agent sse finished with DONE': () => body.includes('[DONE]'),
  });

  if (ok && body.includes('[DONE]')) {
    sseDoneCount.add(1);
  }

  sleep(1);
}
