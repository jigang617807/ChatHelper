import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, formHeaders, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';
import { extractHiddenValue } from './lib/html.js';

const documentId = __ENV.DOC_ID;
const baseQuestion = __ENV.QUESTION || '请总结这个文档最重要的三个要点';
const totalIterations = Number(__ENV.ITERATIONS || 20);
const runId = __ENV.RUN_ID || String(Date.now());

if (!documentId) {
  throw new Error('DOC_ID is required');
}

export const options = {
  noCookiesReset: true,
  scenarios: {
    rag_concurrency: {
      executor: 'shared-iterations',
      vus: vus(1),
      iterations: totalIterations,
      maxDuration: __ENV.MAX_DURATION || '30m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    rag_done_rate: ['rate>0.90'],
  },
};

const ragTotalMs = new Trend('rag_total_ms', true);
const ragFirstByteMs = new Trend('rag_first_byte_ms', true);
const ragDoneRate = new Rate('rag_done_rate');
const ragCompleted = new Counter('rag_completed_requests');

export default function () {
  ensureLoggedIn();
  const page = http.get(`${BASE_URL}/chat/start?docId=${encodeURIComponent(documentId)}`);
  const conversationId = extractHiddenValue(page.body, 'convId');
  const marker = `${runId}-${__VU}-${__ITER}`;
  const question = `${baseQuestion}（并发测试编号 ${marker}）`;

  if (!conversationId) {
    ragDoneRate.add(false);
    return;
  }

  const response = http.get(
    `${BASE_URL}/chat/ask?conversationId=${encodeURIComponent(conversationId)}`
      + `&documentId=${encodeURIComponent(documentId)}&question=${encodeURIComponent(question)}`,
    { timeout: '300s', headers: { Accept: 'text/event-stream' } }
  );
  const done = check(response, {
    'rag sse status is 200': (r) => r.status === 200,
    'rag sse contains DONE': (r) => String(r.body || '').includes('[DONE]'),
  });

  ragTotalMs.add(response.timings.duration);
  ragFirstByteMs.add(response.timings.waiting);
  ragDoneRate.add(done);
  if (done) {
    ragCompleted.add(1);
  }
}
