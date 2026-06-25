import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, formHeaders, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

const documentId = __ENV.DOC_ID || '';
const iterations = Number(__ENV.ITERATIONS || 12);
const runId = __ENV.RUN_ID || String(Date.now());
let sessionId = null;

export const options = {
  noCookiesReset: true,
  scenarios: {
    agent_enhanced_workflow: {
      executor: 'per-vu-iterations',
      vus: vus(1),
      iterations,
      maxDuration: __ENV.MAX_DURATION || '60m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    agent_task_success: ['rate>0.80'],
    agent_done_rate: ['rate>0.90'],
    agent_expected_tools_rate: ['rate>0.70'],
    agent_min_tool_count_rate: ['rate>0.70'],
  },
};

const agentTotalMs = new Trend('agent_total_ms', true);
const agentFirstByteMs = new Trend('agent_first_byte_ms', true);
const agentStepCount = new Trend('agent_step_count', true);
const agentToolCallCount = new Trend('agent_tool_call_count', true);
const agentDistinctToolCount = new Trend('agent_distinct_tool_count', true);
const agentToolLatencyMs = new Trend('agent_tool_latency_ms', true);
const agentDoneRate = new Rate('agent_done_rate');
const agentTaskSuccess = new Rate('agent_task_success');
const agentToolFailureRate = new Rate('agent_tool_failure_rate');
const agentMaxStepRate = new Rate('agent_max_step_rate');
const agentExpectedToolsRate = new Rate('agent_expected_tools_rate');
const agentMinToolCountRate = new Rate('agent_min_tool_count_rate');
const agentSingleToolSuccess = new Rate('agent_single_tool_success');
const agentTwoToolSuccess = new Rate('agent_two_tool_success');
const agentMultiToolSuccess = new Rate('agent_multi_tool_success');
const agentCompletedTasks = new Counter('agent_completed_tasks');
const agentSingleToolTasks = new Counter('agent_single_tool_tasks');
const agentTwoToolTasks = new Counter('agent_two_tool_tasks');
const agentMultiToolTasks = new Counter('agent_multi_tool_tasks');

export default function () {
  ensureLoggedIn();
  if (!sessionId) {
    sessionId = createSession();
  }
  if (!sessionId) {
    agentDoneRate.add(false);
    agentTaskSuccess.add(false);
    agentExpectedToolsRate.add(false);
    agentMinToolCountRate.add(false);
    return;
  }

  clearSession();
  const task = tasks()[__ITER % tasks().length];
  countCategory(task.category);

  const response = http.get(
    `${BASE_URL}/agent/ask?sessionId=${encodeURIComponent(sessionId)}&question=${encodeURIComponent(task.prompt)}`,
    { timeout: '420s', headers: { Accept: 'text/event-stream' } }
  );
  const done = response.status === 200 && String(response.body || '').includes('[DONE]');
  agentDoneRate.add(done);
  agentTotalMs.add(response.timings.duration);
  agentFirstByteMs.add(response.timings.waiting);

  const steps = readSteps();
  const errors = steps.filter(step => step.stepType === 'ERROR' || step.status === 'FAILED');
  const finals = steps.filter(step => step.stepType === 'FINAL');
  const toolCalls = steps.filter(step => step.stepType === 'TOOL_CALL');
  const toolResults = steps.filter(step => step.stepType === 'TOOL_RESULT');
  const calledTools = toolCalls.map(step => String(step.toolName || ''));
  const distinctTools = Array.from(new Set(calledTools.filter(Boolean)));
  const maxStep = errors.some(step => String(step.errorMessage || '').includes('Max ReAct steps exceeded'));
  const success = done && finals.length > 0 && errors.length === 0;
  const expectedToolsOk = task.expectedTools.every(tool => calledTools.includes(tool));
  const minToolCountOk = toolCalls.length >= task.minToolCalls;

  agentStepCount.add(steps.length);
  agentToolCallCount.add(toolCalls.length);
  agentDistinctToolCount.add(distinctTools.length);
  toolResults.forEach(step => {
    if (step.latencyMs !== null && step.latencyMs !== undefined) {
      agentToolLatencyMs.add(Number(step.latencyMs));
    }
  });
  agentToolFailureRate.add(errors.length > 0);
  agentMaxStepRate.add(maxStep);
  agentTaskSuccess.add(success);
  agentExpectedToolsRate.add(expectedToolsOk);
  agentMinToolCountRate.add(minToolCountOk);
  recordCategorySuccess(task.category, success && expectedToolsOk && minToolCountOk);
  if (success) {
    agentCompletedTasks.add(1);
  }

  check(response, {
    'agent sse contains DONE': () => done,
    'agent produced FINAL without ERROR': () => success,
    'agent called expected tools': () => expectedToolsOk,
    'agent reached min tool count': () => minToolCountOk,
  });
}

function createSession() {
  const title = `loadtest-enhanced-${runId}-vu${__VU}`;
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

function readSteps() {
  const stepsResponse = http.get(`${BASE_URL}/agent/steps?sessionId=${encodeURIComponent(sessionId)}`);
  try {
    return stepsResponse.json() || [];
  } catch (_) {
    return [];
  }
}

function countCategory(category) {
  if (category === 'single') {
    agentSingleToolTasks.add(1);
  } else if (category === 'two') {
    agentTwoToolTasks.add(1);
  } else if (category === 'multi') {
    agentMultiToolTasks.add(1);
  }
}

function recordCategorySuccess(category, success) {
  if (category === 'single') {
    agentSingleToolSuccess.add(success);
  } else if (category === 'two') {
    agentTwoToolSuccess.add(success);
  } else if (category === 'multi') {
    agentMultiToolSuccess.add(success);
  }
}

function tasks() {
  const values = [
    {
      category: 'single',
      minToolCalls: 1,
      expectedTools: ['calculator'],
      prompt: '请严格调用 calculator 工具计算 12345 * 6789，然后用中文给出计算式和结果。'
    },
    {
      category: 'single',
      minToolCalls: 1,
      expectedTools: ['date_time'],
      prompt: '请严格调用 date_time 工具获取当前服务器时间，然后用中文回答当前日期和时间。'
    },
    {
      category: 'single',
      minToolCalls: 1,
      expectedTools: ['document_list'],
      prompt: '请严格调用 document_list 工具列出我当前上传的文档，并说明哪些文档状态可用于 RAG。'
    },
    {
      category: 'single',
      minToolCalls: 1,
      expectedTools: ['tool_list'],
      prompt: '请严格调用 tool_list 工具列出你当前可用的工具，并按用途简要归类。'
    },
    {
      category: 'single',
      minToolCalls: 1,
      expectedTools: ['rag_search'],
      prompt: `请严格调用 rag_search 工具，基于文档 ID ${documentId} 总结三个核心要点，并保留引用来源。`
    },
    {
      category: 'two',
      minToolCalls: 2,
      expectedTools: ['document_list', 'rag_search'],
      prompt: `请严格按顺序执行：第一步调用 document_list 确认我的文档；第二步调用 rag_search 查询文档 ID ${documentId} 的核心内容；最后用中文输出三条摘要和引用来源。`
    },
    {
      category: 'two',
      minToolCalls: 2,
      expectedTools: ['rag_search', 'calculator'],
      prompt: `请严格按顺序执行：第一步调用 rag_search 查询文档 ID ${documentId} 中关于性能或系统设计的内容；第二步调用 calculator 计算 2894.56 - 1686.75；最后说明这个差值代表缓存节省的首字节毫秒数，并结合文档证据回答。`
    },
    {
      category: 'two',
      minToolCalls: 2,
      expectedTools: ['date_time', 'rag_search'],
      prompt: `请严格按顺序执行：第一步调用 date_time 获取当前时间；第二步调用 rag_search 查询文档 ID ${documentId} 的项目摘要；最后生成一段带当前时间的测试报告摘要。`
    },
    {
      category: 'multi',
      minToolCalls: 3,
      expectedTools: ['tool_list', 'document_list', 'rag_search'],
      prompt: `请严格按顺序执行：第一步调用 tool_list 查看能力；第二步调用 document_list 查看文档；第三步调用 rag_search 查询文档 ID ${documentId} 的项目难点；最后整理成一段面试回答。`
    },
    {
      category: 'multi',
      minToolCalls: 2,
      expectedTools: ['rag_search', 'pdf_generation'],
      prompt: `请严格按顺序执行：第一步调用 rag_search 查询文档 ID ${documentId} 的项目亮点；第二步调用 pdf_generation 生成一个名为 agent-rag-summary.pdf 的简短英文 PDF 报告；最后说明 PDF 文件已生成并总结内容。`
    },
    {
      category: 'multi',
      minToolCalls: 3,
      expectedTools: ['date_time', 'rag_search', 'calculator'],
      prompt: `请严格按顺序执行：第一步调用 date_time 获取当前时间；第二步调用 rag_search 查询文档 ID ${documentId} 的 RAG 性能信息；第三步调用 calculator 计算 10316.43 - 8292.03；最后说明 Agent P95 相比 RAG 单并发 P95 大约多多少毫秒。`
    },
  ];
  if (!documentId) {
    return values.filter(task => !task.expectedTools.includes('rag_search'));
  }
  return values;
}
