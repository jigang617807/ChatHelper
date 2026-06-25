import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, formHeaders, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';
import { extractHiddenValue } from './lib/html.js';

const documentId = __ENV.DOC_ID;
const baseQuestion = __ENV.QUESTION || '请概括这个文档的核心内容';
const iterations = Number(__ENV.ITERATIONS || 3);
const runId = __ENV.RUN_ID || String(Date.now());

if (!documentId) {
  throw new Error('DOC_ID is required');
}

export const options = {
  noCookiesReset: true,
  scenarios: {
    cache_pairs: {
      executor: 'per-vu-iterations',
      vus: vus(1),
      iterations,
      maxDuration: __ENV.MAX_DURATION || '30m',
    },
  },
  thresholds: {
    cache_pair_success: ['rate>0.90'],
  },
};

const coldTotalMs = new Trend('rag_cold_total_ms', true);
const hotTotalMs = new Trend('rag_hot_total_ms', true);
const coldFirstByteMs = new Trend('rag_cold_first_byte_ms', true);
const hotFirstByteMs = new Trend('rag_hot_first_byte_ms', true);
const cacheSavedMs = new Trend('cache_saved_first_byte_ms', true);
const cacheSpeedupPct = new Trend('cache_first_byte_speedup_pct', true);
const cachePairSuccess = new Rate('cache_pair_success');
const cachePairs = new Counter('cache_pairs');

export default function () {
  ensureLoggedIn();
  const marker = `${runId}-${__VU}-${__ITER}`;
  const question = `${baseQuestion}（性能测试编号 ${marker}）`;

  clearConversation();
  const coldConversation = startConversation();
  const cold = ask(coldConversation, question);

  clearConversation();
  const hotConversation = startConversation();
  const hot = ask(hotConversation, question);
  clearConversation();

  const success = cold.done && hot.done;
  cachePairSuccess.add(success);
  if (!success) {
    return;
  }

  coldTotalMs.add(cold.totalMs);
  hotTotalMs.add(hot.totalMs);
  coldFirstByteMs.add(cold.firstByteMs);
  hotFirstByteMs.add(hot.firstByteMs);
  cacheSavedMs.add(cold.firstByteMs - hot.firstByteMs);
  cacheSpeedupPct.add(cold.firstByteMs > 0 ? ((cold.firstByteMs - hot.firstByteMs) / cold.firstByteMs) * 100 : 0);
  cachePairs.add(1);
}

function clearConversation() {
  http.post(`${BASE_URL}/chat/clear`, { documentId }, formHeaders());
}

function startConversation() {
  const response = http.get(`${BASE_URL}/chat/start?docId=${encodeURIComponent(documentId)}`);
  const conversationId = extractHiddenValue(response.body, 'convId');
  check(response, {
    'chat page is available': (r) => r.status === 200,
    'conversation id is present': () => Boolean(conversationId),
  });
  return conversationId;
}

function ask(conversationId, question) {
  if (!conversationId) {
    return { done: false, totalMs: 0, firstByteMs: 0 };
  }
  const response = http.get(
    `${BASE_URL}/chat/ask?conversationId=${encodeURIComponent(conversationId)}`
      + `&documentId=${encodeURIComponent(documentId)}&question=${encodeURIComponent(question)}`,
    { timeout: '240s', headers: { Accept: 'text/event-stream' } }
  );
  const done = response.status === 200 && String(response.body || '').includes('[DONE]');
  return {
    done,
    totalMs: response.timings.duration,
    firstByteMs: response.timings.waiting,
  };
}
