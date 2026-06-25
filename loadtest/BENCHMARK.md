# Agentic RAG 一键基准测试

这套程序只位于 `loadtest/`，不会修改业务代码。它针对当前项目的真实链路测试：

1. 登录后首页和文档列表的基础 HTTP 性能。
2. PDF 上传受理延迟，以及 RabbitMQ -> PDF 解析 -> Embedding -> PostgreSQL/pgvector -> Elasticsearch 的完整 ETL 时间。
3. 同一问题首次检索和 Redis 热路径的配对对照。
4. RAG SSE 在不同并发下的首字节时间、完整响应时间和 `[DONE]` 完成率。
5. Agent 固定任务集的结构化成功率、执行步骤数、工具调用数、工具耗时和最大步数触发率。

## 运行前准备

1. 启动 PostgreSQL、Redis、Elasticsearch、RabbitMQ 和 Spring Boot 项目。
2. 确认浏览器可以访问 `http://localhost:8080/auth/login`。
3. 安装 k6：

```powershell
winget install k6.k6
k6 version
```

4. 准备一个已经处理完成、状态为 `COMPLETED` 的文档，并记住文档 ID。
5. 如需测试上传链路，准备测试 PDF：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\scripts\download-pdfs.ps1
```

## 一键执行

在项目根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\run-benchmark.ps1 -DocId 你的文档ID
```

默认使用 `standard` 档位。第一次建议先跑低成本检查：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\run-benchmark.ps1 -Profile smoke -DocId 你的文档ID
```

用于形成简历数据时，再运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\run-benchmark.ps1 `
  -Profile standard `
  -DocId 你的文档ID `
  -PdfPath .\loadtest\test-data\small.pdf
```

更高样本量和更高并发：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\run-benchmark.ps1 -Profile full -DocId 你的文档ID
```

`full` 会产生较多 Embedding 和大模型调用，请确认 API 额度和限流配置。

## 常用开关

```powershell
# 不测试上传，避免产生 Embedding 成本
... -SkipUpload

# 不测试 RAG
... -SkipRag

# 不测试 Agent
... -SkipAgent

# 保留压测上传的文档；默认测试完成后自动删除
... -KeepUploadedDocs

# 指定已有测试账号
... -Username loadtest -Password loadtest123 -AutoRegister $false
```

## 三档规模

| 档位 | 作用 | 特点 |
| --- | --- | --- |
| `smoke` | 验证脚本和环境 | 调用少，不能用于稳定 P95 |
| `standard` | 简历数据 | 每个 RAG 并发级别 20 个样本，另含 10 组缓存配对、5 份 ETL 文档和 20 个 Agent 任务 |
| `full` | 压力拐点观察 | 每个 RAG 并发级别 50 个样本并覆盖到 8 并发，API 成本较高 |

## 输出目录

每次运行都会创建：

```text
loadtest/results/时间戳-档位/
  manifest.json              测试参数、机器配置、Git 提交号
  *.summary.json             k6 原始汇总，数字原始来源
  *.log                      每个场景的控制台记录
  benchmark-metrics.csv      可筛选的统一指标表
  benchmark-report.md        自动报告和简历候选句
```

不要只保留最终简历数字。至少同时保留 `manifest.json`、对应的 `summary.json` 和报告，面试追问时才能说明测试环境、样本数和口径。

## 指标口径

### 上传与 ETL

- `upload_accept_ms`：上传请求落盘、创建 Document 并投递 RabbitMQ 后返回重定向的时间。
- `etl_complete_ms`：上传接口返回后，到文档页面显示 `COMPLETED` 的时间，包含排队、解析、Embedding、PG 与 ES 写入。
- `etl_success`：在超时时间内进入 `COMPLETED` 的比例。

### Redis 冷热对照

脚本为每组实验生成一个从未使用过的问题：

1. 清空聊天历史并执行首次查询，触发 Embedding、pgvector、BM25、RRF 和缓存写入。
2. 再次清空聊天历史，以完全相同的问题执行第二次查询，命中 Redis 检索缓存。

这样避免第二次请求因为对话历史变长而失去可比性。重点观察首字节时间，不把 LLM 完整生成波动误认为缓存收益。

### RAG 并发

- `rag_first_byte_ms`：请求到 SSE 首字节，包含同步检索及模型首 token 等待。
- `rag_total_ms`：到收到完整响应的时间。
- `rag_done_rate`：响应正常包含 `[DONE]` 的比例。

### Agent

- `agent_task_success`：产生 FINAL 且执行链没有 ERROR。
- `agent_step_count`：PLAN、TOOL_CALL、TOOL_RESULT、FINAL、ERROR 的总步骤数。
- `agent_tool_latency_ms`：业务工具本身耗时，不包含模型规划时间。
- `agent_max_step_rate`：达到 ReAct 最大步数的任务比例。

## 简历使用原则

- 上传 P95、ETL P95、RAG P95 必须同时写测试文件大小、并发量和成功率。
- 缓存收益优先引用配对测试的平均首字节降幅，并注明配对数量。
- 少于约 20 个有效样本时，不要强调 P95，优先使用平均值或中位数。
- Agent 结构化成功率不等于答案准确率；答案质量需要单独任务集和人工判定。
- 外部模型限流、网络和 API 版本会显著影响结果，报告中的 Git 提交和测试日期必须保留。
