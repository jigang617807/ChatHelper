# Agentic RAG 项目简历数据整理

## 推荐写法

### 综合版

- 基于 Spring Boot 与 Spring AI 构建私有知识库 Agentic RAG 系统，支持 PDF 异步解析、向量化入库、pgvector + Elasticsearch 双路召回、RRF 融合排序、Redis 检索缓存、SSE 流式问答及 ReAct Agent 工具编排。
- 针对 RAG 检索效果设计离线 Chunk 参数评测，覆盖 4 份文档、50 个标注问题、5 组 `chunkSize/overlap` 配置；最终采用 `800/200` 切分策略，取得 `Recall@5=96%`、`MRR=0.7974`、`Top5` 冗余度 `6.88%`。
- 基于 k6 构建本地端到端压测脚本，覆盖登录态页面、Redis 冷热缓存、RAG SSE 并发和 ReAct Agent 工作流；在 `standard` 档测试中，RAG 在 1/2/4 并发下均保持 `100%` 完成率。
- 针对 Redis 检索缓存设计 10 组冷热配对实验，热路径将 RAG 首字节平均耗时由 `2894.56ms` 降至 `1686.75ms`，平均降低约 `39.54%`。
- 针对 ReAct Agent 设计 20 次固定任务集测试，覆盖计算、时间、文档列表、工具列表和 RAG 工具调用，结构化成功率 `100%`，工具调用失败率 `0%`，端到端 `P95=10.32s`。

### 精简版

- 构建基于 Spring Boot、Spring AI 的 Agentic RAG 知识库系统，支持 PDF 异步解析、pgvector + Elasticsearch 混合检索、Redis 检索缓存、SSE 流式问答和 ReAct 工具编排。
- 设计 4 文档、50 问题、5 组参数的 Chunk 离线评测，选用 `800/200` 切分策略，取得 `Recall@5=96%`、`MRR=0.7974`、`Top5` 冗余度 `6.88%`。
- 使用 k6 完成 RAG/缓存/Agent 端到端压测；在 1/2/4 并发 RAG 测试下完成率均为 `100%`，Redis 热路径将首字节平均耗时降低约 `39.54%`，Agent 固定任务集结构化成功率 `100%`。

### 更工程化的写法

- 将文档上传后的解析、切分、Embedding、PG/ES 写入改造为 RabbitMQ 异步 ETL 链路，并通过文档状态机、手动 ACK、延迟重试、死信队列、补偿扫描和幂等索引设计提升处理可靠性。
- 构建 pgvector Top20 与 Elasticsearch BM25 Top20 双路召回，并通过 RRF 融合选取 Top10 证据 Chunk；在 4 份文档、50 个标注问题的离线评测中，`800/200` 切分配置取得 `Recall@5=96%`。
- 实现基于 `userId + documentId + 归一化问题哈希` 的 Redis 检索证据缓存，并通过文档级索引集合实现精确失效；10 组冷热配对实验中首字节平均耗时降低 `39.54%`。
- 基于 k6 编写本地端到端压测脚本，覆盖 RAG SSE、缓存命中和 ReAct Agent 工具链；`standard` 档测试下 RAG 1/2/4 并发完成率均为 `100%`，Agent 20 次固定任务结构化成功率 `100%`。

## 可引用数据

| 类型 | 指标 | 数值 | 备注 |
| --- | --- | --- | --- |
| Chunk 评测 | 文档数 / 问题数 / 参数组 | 4 份 / 50 个 / 5 组 | 离线检索评测，不是最终问答准确率 |
| Chunk 评测 | 选用配置 | `800/200` | chunkSize=800, overlap=200 |
| Chunk 评测 | Recall@5 | `96%` | 证据 Chunk 召回率 |
| Chunk 评测 | MRR | `0.7974` | 首个相关 Chunk 排名质量 |
| Chunk 评测 | Top5 冗余度 | `6.88%` | Top5 重复上下文比例较低 |
| Chunk 评测 | 平均 Chunk 数 / 文档 | `26.5` | `800/200` 配置 |
| Chunk 评测 | 平均 Chunk 字符数 | `754.67` | `800/200` 配置 |
| 页面链路 | 页面访问轮次 | `519` | k6 standard |
| 页面链路 | 页面成功率 | `100%` | `auth-pages` |
| 页面链路 | 首页 P95 | `10.34ms` | 本地页面链路 |
| 页面链路 | 文档列表 P95 | `113.49ms` | 本地页面链路 |
| Redis 缓存 | 冷热配对数 | `10` 组 | 相同问题冷/热对照 |
| Redis 缓存 | 冷查询首字节均值 | `2894.56ms` | RAG 首字节时间 |
| Redis 缓存 | 热查询首字节均值 | `1686.75ms` | 命中 Redis 检索缓存 |
| Redis 缓存 | 首字节平均降幅 | `39.54%` | 可写简历 |
| RAG 并发 | 1 并发完成率 / P95 | `100%` / `8.29s` | 20 次请求 |
| RAG 并发 | 2 并发完成率 / P95 | `100%` / `10.23s` | 20 次请求 |
| RAG 并发 | 4 并发完成率 / P95 | `100%` / `16.94s` | 20 次请求 |
| Agent | 固定任务数 | `20` 次 | 计算、时间、文档列表、工具列表、RAG |
| Agent | 结构化成功率 | `100%` | FINAL 且无 ERROR |
| Agent | 工具失败率 | `0%` | 工具链执行无失败 |
| Agent | 端到端 P95 | `10.32s` | 包含外部模型延迟 |
| Agent | 平均步骤数 / 工具调用数 | `5` / `1` | 每轮 ReAct 链路 |

## 数据来源

- `loadtest/rag-eval/results/chunk_eval_summary.csv`
- `loadtest/rag-eval/results/chunk_eval_detail.csv`
- `loadtest/results/20260624-223356-standard/benchmark-report.md`
- `loadtest/results/20260624-223356-standard/benchmark-metrics.csv`
- `loadtest/results/20260624-223356-standard/manifest.json`

## 表述边界

- `Recall@5=96%` 是 Chunk 证据召回率，不是最终答案准确率。
- RAG 和 Agent 延迟包含外部大模型、Embedding API、网络和本地服务共同耗时，不是纯 Java 接口耗时。
- 本次 k6 结果来自本地 `standard` 档，机器为 i5-10200H、8 逻辑处理器、15.87GB 内存。
- 本次压测使用 `-SkipUpload`，没有覆盖 PDF 上传和 RabbitMQ 异步 ETL 的耗时，因此简历里不要写上传/解析 P95。
