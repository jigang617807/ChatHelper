import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, duration, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

const docId = __ENV.DOC_ID;
const question = __ENV.QUESTION || 'Summarize this document in three sentences.';

if (!docId) {
  throw new Error('DOC_ID is required, for example: -e DOC_ID=1');
}

export const options = {
  vus: vus(2),
  duration: duration('1m'),
  thresholds: {
    http_req_failed: ['rate<0.1'],
    http_req_duration: ['p(95)<60000'],
    sse_done_count: ['count>0'],
  },
};

const sseDoneCount = new Counter('sse_done_count');
const sseTotalMs = new Trend('sse_total_ms');
const sseFirstByteMs = new Trend('sse_first_byte_ms');

function extractConversationId(html) {
  const match = html.match(/id="convId"\s+value="([^"]+)"/);
  return match ? match[1] : null;
}

export default function () {
  ensureLoggedIn();

  const start = http.get(`${BASE_URL}/chat/start?docId=${encodeURIComponent(docId)}`);
  const conversationId = extractConversationId(start.body || '');
  check(start, {
    'chat start status is 200': (r) => r.status === 200,
    'conversation id exists': () => Boolean(conversationId),
  });

  if (!conversationId) {
    sleep(1);
    return;
  }

  const begin = Date.now();
  const response = http.get(
    `${BASE_URL}/chat/ask?conversationId=${encodeURIComponent(conversationId)}&documentId=${encodeURIComponent(docId)}&question=${encodeURIComponent(question)}`,
    {
      timeout: '180s',
      headers: {
        Accept: 'text/event-stream',
      },
    }
  );
  const total = Date.now() - begin;
  const firstByte = response.timings.waiting;
  const body = response.body || '';

  sseTotalMs.add(total);
  sseFirstByteMs.add(firstByte);

  const ok = check(response, {
    'rag sse status is 200': (r) => r.status === 200,
    'rag sse finished with DONE': () => body.includes('[DONE]'),
  });

  if (ok && body.includes('[DONE]')) {
    sseDoneCount.add(1);
  }

  sleep(1);
}
