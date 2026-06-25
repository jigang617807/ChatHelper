import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, formHeaders, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

const documentId = __ENV.DOC_ID || '';
const iterations = Number(__ENV.ITERATIONS || 4);
const runId = __ENV.RUN_ID || String(Date.now());
let sessionId = null;

export const options = {
  noCookiesReset: true,
  scenarios: {
    agent_workflow: {
      executor: 'per-vu-iterations',
      vus: vus(1),
      iterations,
      maxDuration: __ENV.MAX_DURATION || '45m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    agent_task_success: ['rate>0.80'],
    agent_done_rate: ['rate>0.90'],
  },
};

const agentTotalMs = new Trend('agent_total_ms', true);
const agentFirstByteMs = new Trend('agent_first_byte_ms', true);
const agentStepCount = new Trend('agent_step_count', true);
const agentToolCallCount = new Trend('agent_tool_call_count', true);
const agentToolLatencyMs = new Trend('agent_tool_latency_ms', true);
const agentDoneRate = new Rate('agent_done_rate');
const agentTaskSuccess = new Rate('agent_task_success');
const agentToolFailureRate = new Rate('agent_tool_failure_rate');
const agentMaxStepRate = new Rate('agent_max_step_rate');
const agentCompletedTasks = new Counter('agent_completed_tasks');

export default function () {
  ensureLoggedIn();
  if (!sessionId) {
    sessionId = createSession();
  }
  if (!sessionId) {
    agentDoneRate.add(false);
    agentTaskSuccess.add(false);
    return;
  }

  clearSession();
  const task = tasks()[__ITER % tasks().length];
  const response = http.get(
    `${BASE_URL}/agent/ask?sessionId=${encodeURIComponent(sessionId)}&question=${encodeURIComponent(task)}`,
    { timeout: '360s', headers: { Accept: 'text/event-stream' } }
  );
  const done = response.status === 200 && String(response.body || '').includes('[DONE]');
  agentDoneRate.add(done);
  agentTotalMs.add(response.timings.duration);
  agentFirstByteMs.add(response.timings.waiting);

  const stepsResponse = http.get(`${BASE_URL}/agent/steps?sessionId=${encodeURIComponent(sessionId)}`);
  let steps = [];
  try {
    steps = stepsResponse.json() || [];
  } catch (_) {
    steps = [];
  }

  const errors = steps.filter(step => step.stepType === 'ERROR' || step.status === 'FAILED');
  const finals = steps.filter(step => step.stepType === 'FINAL');
  const toolCalls = steps.filter(step => step.stepType === 'TOOL_CALL');
  const toolResults = steps.filter(step => step.stepType === 'TOOL_RESULT');
  const maxStep = errors.some(step => String(step.errorMessage || '').includes('Max ReAct steps exceeded'));
  const success = done && finals.length > 0 && errors.length === 0;

  agentStepCount.add(steps.length);
  agentToolCallCount.add(toolCalls.length);
  toolResults.forEach(step => {
    if (step.latencyMs !== null && step.latencyMs !== undefined) {
      agentToolLatencyMs.add(Number(step.latencyMs));
    }
  });
  agentToolFailureRate.add(errors.length > 0);
  agentMaxStepRate.add(maxStep);
  agentTaskSuccess.add(success);
  if (success) {
    agentCompletedTasks.add(1);
  }

  check(response, {
    'agent sse contains DONE': () => done,
    'agent produced FINAL without ERROR': () => success,
  });
}

function createSession() {
  const title = `loadtest-${runId}-vu${__VU}`;
  const response = http.post(
    `${BASE_URL}/agent/session/create`,
    { title },
    { ...formHeaders(), redirects: 0 }
  );
  const location = String(response.headers.Location || '');
  const match = location.match(/sessionId=(\d+)/);
  return match ? match[1] : null;
}

function clearSession() {
  http.post(`${BASE_URL}/agent/clear`, { sessionId }, formHeaders());
}

function tasks() {
  const values = [
    '请调用计算器工具计算 12345 乘以 6789，只返回计算过程和结果。',
    '请调用时间工具告诉我当前日期和时间。',
    '请调用文档列表工具列出我已经上传的文档。',
    '请调用工具列表说明你当前有哪些可用工具。',
  ];
  if (documentId) {
    values.push(`请调用 RAG 工具，基于文档 ID ${documentId} 总结三个核心要点，并给出引用来源。`);
  }
  return values;
}
