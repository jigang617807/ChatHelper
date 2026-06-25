# Agentic RAG 项目：难点回答与简历量化建议

## 一、面试主回答（建议先讲 3 个）

### 难点 1：异步文档处理的可靠性和幂等性

**可直接背诵：**

这个项目第一个难点，是 PDF 解析、文本切分、Embedding 和双索引写入都比较耗时，不能直接放在上传请求里同步执行，所以我用 RabbitMQ 把上传和 ETL 解耦。但异步化以后又会带来消息重复、处理中宕机和任务丢失的问题。我的解决方案不是只加一个队列，而是做了完整的可靠性闭环：通过 `PENDING、PROCESSING、COMPLETED、FAILED_RETRYABLE、FAILED` 状态控制任务流转；消费者使用手动 ACK；临时异常进入延迟 30 秒的重试队列，默认最多重试 3 次，超过次数进入死信队列；如果重试消息发送失败，就 NACK 原消息并重新入队。同时，我通过条件更新抢占处理权、PostgreSQL 的 `(document_id, chunk_index)` 唯一约束和 Elasticsearch 的确定性 ID，保证重复投递不会生成重复 Chunk。最后再通过每 60 秒执行一次的补偿扫描，重新投递长时间未完成的任务。

**面试官可能追问：为什么不能只依赖 RabbitMQ 不重复投递？**

RabbitMQ 提供的是至少一次投递语义，消费者处理成功但 ACK 丢失时，消息仍可能再次投递，所以业务端必须自己保证幂等。队列可靠性解决消息尽量不丢，状态机和唯一约束解决消息重复时数据不乱，这是两个不同层面。

**代码依据：**

- `src/main/java/com/example/demo/service/DocProcessor.java`
- `src/main/java/com/example/demo/config/RabbitConfig.java`
- `src/main/java/com/example/demo/service/DocumentService.java`
- `src/main/java/com/example/demo/entity/DocumentChunk.java`
- `src/main/resources/application.yml`

---

### 难点 2：向量召回和 BM25 分数无法直接融合

**可直接背诵：**

第二个难点是混合检索。pgvector 擅长语义相似问题，Elasticsearch BM25 擅长专有名词和关键词精确匹配，但两路分数的量纲不同，直接做线性相加会强依赖人工调权。我最终采用 RRF，分别取向量召回 Top20 和 BM25 Top20，只根据各自在结果列表中的排名计算融合分数，再选取最终 Top10。这样既避免直接比较两种异构分数，也能让同时被两路命中的 Chunk 获得更高排名。另外 BM25 查询单独做了异常兜底，ES 不可用时退化为纯向量检索，不让增强链路拖垮主流程。

为了避免 Chunk 参数只靠经验确定，我还做了离线小样本评测：使用 4 份文档、50 个标注问题，对比 5 组 `chunkSize/overlap`。当前简单解析链路使用的 `800/200` 配置取得 `Recall@5=96%`、`MRR=0.7974`、`Top5 冗余度=6.88%`。这里的 96% 是 Chunk 参数实验中的证据召回率，不等同于最终问答正确率。

**面试官可能追问：为什么最终选 800/200，不选 MRR 更高的 1200/300？**

1200/300 的 MRR 和 nDCG 更高，但 Top5 估算上下文成本约为 1958 tokens；800/200 约为 1312 tokens，低约 33%，同时 Recall@5 从 94% 提高到 96%，冗余度也更低。我的取舍是优先保证 Top5 证据覆盖，并控制上下文成本。当然，这只是 4 份文档、50 个问题的小样本结论，扩大数据集后仍需要重新评估。

**评测依据：**

- `loadtest/rag-eval/questions.json`：4 份文档、50 个问题
- `loadtest/rag-eval/results/chunk_eval_summary.csv`：5 组配置及指标
- `loadtest/rag-eval/results/chunk_eval_detail.csv`：逐问题命中明细
- `src/main/java/com/example/demo/service/DocumentParsingService.java`：简单解析使用 `800/200`
- `src/main/java/com/example/demo/service/RagService.java`：双路召回、RRF 和 ES 降级

---

### 难点 3：Redis 缓存不能以牺牲一致性为代价

**可直接背诵：**

第三个难点是缓存设计。最开始如果直接缓存最终回答，模型升级、Prompt 调整或文档更新后容易返回旧答案，所以我只缓存检索结果和证据信息，不缓存模型最终回答。缓存 Key 按 `userId + documentId + 归一化问题哈希` 隔离，默认 TTL 是 1 小时。另一个问题是问题哈希不可枚举，文档删除或重建时不能用 Redis `KEYS` 在线扫描，因此我为每个用户和文档维护一个索引集合，记录该文档产生过的所有真实缓存 Key，失效时通过集合精确批量删除。缓存读取后还会根据数据库中的 Chunk 进行校验，避免引用已经变化的数据。

**面试官可能追问：缓存命中后节省了什么？**

命中后可以跳过 Query Embedding、pgvector 检索、BM25 检索和 RRF 融合，但仍使用当前数据库中的有效 Chunk 构建上下文。因此节省的是检索链路开销，而不是缓存一个可能过期的最终答案。

**代码依据：**

- `src/main/java/com/example/demo/service/RagCachedRetrievalService.java`
- `src/main/java/com/example/demo/service/RagRetrievalCacheService.java`
- `src/main/resources/application.yml`

---

### 难点 4：ReAct Agent 的可控性和可追踪性

**可直接背诵：**

第四个难点是大模型输出不稳定。模型可能输出非法 JSON、调用不存在的工具、参数错误，或者一直循环调用工具。因此我没有把模型输出直接当结果执行，而是定义了 `tool` 和 `finish` 两种动作协议，由后端解析并校验；工具只能从注册表白名单中选择，默认最大执行 8 步，代码层最高限制为 16 步。每轮的 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 和 ERROR 都写入 `agent_step`，并记录工具耗时，便于定位是模型规划慢、工具执行慢还是外部接口失败。当前项目注册了 10 个本地 ReAct 工具组件，新增工具只需要实现统一接口并注册为 Spring Bean。

**面试官可能追问：为什么不完全使用 Spring AI 自动 Tool Calling？**

Spring AI 提供了 ChatModel 和 Tool Calling 的基础能力，但我的场景还需要最大步数、Observation 注入、工具白名单、错误反馈、执行链落库和工作区隔离，所以在它的模型能力之上实现了自己的 ReAct 控制循环。

**代码依据：**

- `src/main/java/com/example/agent/executor/ReActAgentExecutor.java`
- `src/main/java/com/example/agent/executor/ReActAction.java`
- `src/main/java/com/example/agent/tool/react/ReactToolRegistry.java`
- `src/main/java/com/example/agent/service/AgentStepService.java`
- `src/main/java/com/example/agent/tool/react/`：10 个 `ReactTool` 实现

---

### 难点 5：工具能力开放后的安全边界

**可直接背诵：**

Agent 能网页抓取、下载资源和生成文件以后，难点就不只是功能实现，还包括 SSRF、路径穿越、超大文件和越权访问。我为每个用户和会话创建独立工作目录，所有文件路径都要归一化后校验必须位于当前工作区；URL 下载前检查协议和目标地址；下载文件采用扩展名白名单，默认限制 10MB；RAG 和文档列表工具都从 ToolContext 获取当前用户身份，不能仅凭模型给出的 documentId 越权读取其他用户文档。这样把模型视为不可信的规划者，最终权限仍由后端确定。

**代码依据：**

- `src/main/java/com/example/agent/tool/react/AgentWorkspaceService.java`
- `src/main/java/com/example/agent/tool/react/UrlSafety.java`
- `src/main/java/com/example/agent/tool/react/ResourceDownloadTool.java`
- `src/main/java/com/example/agent/tool/react/RagSearchReactTool.java`

---

### 难点 6：扫描版 PDF 和多模态内容解析

**可直接背诵：**

PDFBox 对文本型 PDF 效果较好，但扫描件可能提取不到文本，图表中的信息也容易丢失。我在解析链路中区分简单文本切分、页面渲染 OCR 和内嵌图片提取：文本为空或页面需要视觉信息时，可以调用 OCR；页面图片默认按 120 DPI 渲染，每页最多处理 20 张符合尺寸条件的图片，OCR 外部调用设置 20 秒超时。这个方案提高了扫描件的可处理性，同时通过图片数量、尺寸和超时限制控制成本。

**需要诚实说明：**

OCR 能力已经有实现和配置，但如果没有针对扫描件建立独立评测集，就不要在简历上写“OCR 准确率提升 xx%”。

**代码依据：**

- `src/main/java/com/example/demo/service/DocumentParsingService.java`
- `src/main/java/com/example/demo/service/OcrService.java`
- `src/main/resources/application.yml`

## 二、简历量化改写

### 版本 A：现在就可以使用

以下数字都有现有代码或评测文件支持。

**项目描述：**

基于 Spring Boot、Spring AI 构建私有知识库 Agentic RAG 平台，支持最大 50MB PDF 上传、异步解析、768 维向量化入库、混合检索、流式问答及 ReAct 工具编排；完成 4 份文档、50 个问题、5 组切分参数的离线检索评测。

**异步削峰 ETL 链路：**

基于 RabbitMQ 解耦 PDF 上传与解析入库，设计主队列、延迟重试队列和死信队列，采用手动 ACK、默认 3 次重试、30 秒重试间隔及 60 秒补偿扫描；结合任务状态机、PG 唯一约束和 ES 确定性 ID 保证重复投递下的幂等处理。

**多路召回混合检索：**

基于 pgvector 与 Elasticsearch 构建双路召回，分别召回 Top20 并通过 RRF 融合选取 Top10；在 4 份文档、50 个标注问题的小样本 Chunk 评测中，`800/200` 配置取得 `Recall@5 96%`、`MRR 0.7974` 和 `Top5 冗余度 6.88%`。

**热点检索缓存：**

按用户、文档及归一化问题哈希构建 Redis 检索结果缓存，默认 TTL 为 1 小时；通过文档级索引集合实现缓存 Key 精确失效，避免在线 `KEYS` 扫描，并在文档重建、删除时同步清理 PG、ES 与 Redis 数据。

**Agentic RAG 工具编排：**

自研 ReAct 执行循环并注册 10 个本地工具组件，通过工具白名单、结构化动作协议和默认 8 步执行上限约束模型行为；将 PLAN、TOOL_CALL、TOOL_RESULT、FINAL、ERROR 全链路落库并记录工具耗时，实现复杂任务可追踪。

### 版本 B：简历空间较紧时使用

- 构建 RabbitMQ 异步文档 ETL，采用手动 ACK、3 次延迟重试、死信队列及分钟级补偿扫描，并通过状态机与双存储确定性 ID 保证重复消费幂等。
- 实现 pgvector Top20 与 BM25 Top20 双路召回及 RRF Top10 融合；在 4 文档、50 问题、5 组参数的离线评测中，当前切分配置取得 Recall@5 96%、MRR 0.7974。
- 自研默认最多 8 步的 ReAct 执行器，注册 10 个本地工具并持久化 5 类执行步骤，实现工具白名单控制、耗时观测和失败追踪。

## 三、哪些数字暂时不能直接写

以下表述需要实际运行压测或 A/B 实验后才能写：

- “上传接口响应时间降低 xx%”
- “Redis 使平均响应时间降低 xx%”
- “系统支持 xx QPS / xx 并发用户”
- “RRF 相比纯向量检索 Recall 提升 xx%”
- “失败任务恢复率达到 99.9%”
- “Agent 任务成功率达到 xx%”

当前仓库有 k6 脚本和阈值，但阈值只是测试通过标准，不是实测结果。例如 `p(95)<1000ms` 不能改写为“P95 为 1 秒”。

## 四、建议补测后再增加的数字

### 1. Redis 冷热查询对比

对同一文档和同一问题分别执行首次冷查询与缓存命中查询，至少各运行 30 次，记录：

- 平均耗时、P95、P99
- Query Embedding 调用次数
- pgvector 和 Elasticsearch 查询次数
- Redis 命中率

简历模板：

> 通过 Redis 缓存复用检索证据，在 xx 次重复问题测试中将 P95 从 xx ms 降至 xx ms，降低 xx%，缓存命中率达到 xx%。

### 2. 上传接口同步与异步对比

对相同大小 PDF 比较“接口只落盘并投递消息”和“同步完成解析入库”的响应时间，测试 1MB、5MB、25MB 三档文件。

简历模板：

> 将文档解析与向量化入库改造为 RabbitMQ 异步链路，针对 1MB-25MB PDF 测试，上传接口 P95 从 xx s 降至 xx ms。

### 3. 混合检索消融实验

在同一批问题上分别测试：

- 纯向量检索
- 纯 BM25
- 向量 + BM25 + RRF

统一输出 Recall@3、Recall@5、MRR、nDCG@5。

简历模板：

> 在 xx 份文档、xx 个标注问题上，相较纯向量召回，混合检索将 Recall@5 从 xx% 提升至 xx%，MRR 提升 xx%。

### 4. Agent 任务成功率

建立固定任务集，例如文档问答、跨文档总结、网页搜索后抓取、资源下载和 PDF 生成，每类至少 20 条，记录：

- 最终任务成功率
- 平均 ReAct 步数
- 最大步数触发率
- 工具调用失败率
- 平均总耗时和各工具耗时

简历模板：

> 在 xx 条固定 Agent 任务集上取得 xx% 的任务完成率，平均执行 xx 步，最大步数触发率为 xx%。

## 五、面试时的数字口径

1. `Recall@5 96%` 是离线 Chunk 参数评测结果，不是最终回答正确率。
2. 评测规模是 4 份文档、50 个问题、5 组参数，属于小样本工程选型实验。
3. `Top20 + Top20 -> Top10`、1 小时 TTL、3 次重试、8 步上限等是系统配置或能力边界，不是性能提升比例。
4. 只有拿到真实 k6 或 A/B 输出后，才能写 P95、QPS、缓存提速百分比。
5. 面试官追问数据来源时，可以直接回答：“评测问题和 gold keywords 在 `questions.json`，配置汇总在 `chunk_eval_summary.csv`，逐问题明细在 `chunk_eval_detail.csv`。”
