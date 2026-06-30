# Data Agent 智能体研发实习生面试稿

这份稿件按当前 JD 的优先级准备：Agent 架构是主战场，RAG 和数据分析链路是第二主战场，Java、MySQL、Redis、MQ 和大数据生态作为工程底座防守。我的核心项目是一个基于 Spring Boot 和 Spring AI 的私有知识库 Agentic RAG 系统，简历里对应的是 PDF 上传、异步解析、文本切分、Embedding 入库、pgvector + Elasticsearch 混合检索、Redis 检索缓存、SSE 流式问答，以及自研 ReAct Agent 工具编排。

面试时我会把自己定位成“有后端工程基础、做过 Agentic RAG 闭环、理解数据智能体工程化落地”的候选人，而不是只会调大模型 API。这个岗位虽然写了 Java、多线程、JVM、Redis、MQ、Hive、Spark、Flink、ClickHouse，但 JD 的中心词其实是 Data Agent：让大模型通过规划、工具、状态和权限进入数据分析场景。我的回答要尽量从自己的项目切入，再外延到 Data Agent 场景。

## 一、Agent 架构

如果面试官问“你怎么理解 Agent”，我会这样答：我理解的 Agent 不是一次普通 LLM 调用，而是 LLM 加上 Planning、Memory/State、Tools 和执行控制。普通大模型调用通常是输入 prompt、输出答案；工作流通常是人预先定义固定步骤；而 Agent 的核心是模型能根据当前任务和中间观察结果动态决定下一步，比如查文档、搜网页、抓页面、计算、生成 PDF，直到收集到足够证据后再输出最终答案。我的项目里 Agent 的入口在 `AgentService.streamAsk`，它会先创建或获取会话、保存用户消息、构造历史上下文，然后通过 `requiresReactTools` 判断是否需要进入 ReAct 工具链；如果只是普通问答，就直接流式回答，如果涉及文档、RAG、网页、下载、PDF、实时信息或计算，就进入 `ReActAgentExecutor` 执行工具编排。对应代码在 `src/main/java/com/example/agent/service/AgentService.java:73`、`src/main/java/com/example/agent/service/AgentService.java:79` 和 `src/main/java/com/example/agent/service/AgentService.java:136`。

我项目里的 ReAct 链路可以概括为“轻量意图路由 -> Planner 输出动作 JSON -> 工具注册表校验和执行 -> Observation 回填 -> 最终回答合成”。这里我刻意把 planner 和 final answer 拆开了：planner 只输出短 JSON，包含 `type`、`plan`、`toolName`、`arguments`；最终自然语言答案由单独的 synthesizer 根据历史和工具观察结果生成。这样做的原因是我之前意识到，如果一个模型输出既承担结构化动作规划，又承担长篇 Markdown 最终回答，很容易出现 JSON 损坏、字段混杂、回答泄露到动作字段的问题。现在 `ReActAgentExecutor.nextAction` 负责下一步动作，`repairActionJson` 只修复动作 JSON，`synthesizeFinalAnswer` 再生成最终回答，链路更稳。对应代码在 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:129`、`src/main/java/com/example/agent/executor/ReActAgentExecutor.java:146` 和 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:258`。

如果问 ReAct 和 Plan & Execute 的区别，我会从工程角度说：Plan & Execute 更像先拆任务，再按计划逐步执行，适合目标清晰、步骤相对稳定的任务，比如“生成一份调研报告：先检索资料，再整理大纲，再生成 PDF”。ReAct 是 Reasoning 和 Acting 交替，每一步根据上一步 observation 动态选择工具，适合信息不确定、需要边查边判断的任务，比如“这个文档里有没有相关证据，如果没有再去网页搜索”。我项目更偏 ReAct，因为文档是否存在、检索是否命中、网页搜索是否可用都不是预先确定的；但我在 prompt 里也引入了计划意识，每一步都要求模型写 `plan`，并把每一步计划、工具调用、工具结果和错误落库到 `agent_step`，便于追踪和复盘。对应代码在 `src/main/java/com/example/agent/service/AgentStepService.java:27`、`src/main/java/com/example/agent/service/AgentStepService.java:51`、`src/main/java/com/example/agent/service/AgentStepService.java:64` 和 `src/main/java/com/example/agent/service/AgentStepService.java:78`。

如果问 Tool Calling 完整链路，我会这样讲：第一步是工具注册，把本地 `ReactTool` Bean 和 Spring AI `ToolCallbackProvider` 里的工具统一收集到 `ReactToolRegistry`；第二步是生成工具描述，包括 name、source、description、parameters，放进系统 prompt；第三步是模型输出工具名和 arguments；第四步是注册表按技能白名单和数据库开关校验工具是否可用；第五步是真正执行工具，并把结果包装成 observation；第六步是把 observation 回填给下一轮 planner 或最终回答合成器。我的工具不仅有 `rag_search`、`document_list`，也有 `web_search`、`web_scraping`、`calculator`、`date_time`、`pdf_generation`、`resource_download` 等。工具来源还区分 LOCAL 和 MCP，后续如果接入外部数据平台工具，可以复用这个注册与分发层。对应代码在 `src/main/java/com/example/agent/tool/react/ReactToolRegistry.java:27`、`src/main/java/com/example/agent/tool/react/ReactToolRegistry.java:45`、`src/main/java/com/example/agent/tool/react/ReactToolRegistry.java:57` 和 `src/main/java/com/example/agent/tool/react/ReactToolRegistry.java:80`。

如果问 Agent 如何选择工具，我会说：我目前采用的是“规则路由 + ReAct prompt 约束 + 工具白名单”的组合。第一层，`requiresReactTools` 根据文档、搜索、网页、下载、PDF、天气、最新、实时、计算等信号决定是否进入工具链，这是一种低成本、可解释的意图识别。第二层，进入 ReAct 后，系统 prompt 规定了工具选择规则，比如文档目标不清楚先 `document_list`，文档问答用 `rag_search`，公开调研先 `web_search` 再 `web_scraping`，实时问题先 `date_time`。第三层，真正执行前由 `ReactToolRegistry.find` 检查当前技能允许的工具以及后台是否启用，避免模型想调用什么就调用什么。这个设计的优点是可控、可解释，缺点是规则维护成本会随场景增加而上升。后续如果演进到 Data Agent，我会把第一层从关键词路由升级为“轻量分类器 + 规则兜底”，把工具选择日志沉淀成评测集，用于优化工具路由。

如果问多轮状态怎么管理，我会重点讲三个层次。第一层是会话状态，`AgentSession` 保存会话、当前技能、状态、摘要和已经摘要到的消息 ID；第二层是短期上下文，`AgentService.buildHistoryWithSummary` 每次取最近若干轮消息，默认 recent-message-limit 是 12，并且被限制在 4 到 40 之间；第三层是长期摘要记忆，较早的消息会被 `summarizeHistory` 合并成一段不超过 `summary-max-chars` 的中文记忆，默认 3000 字，记录稳定偏好、项目事实、约束和未完成事项。这样做不是为了“无限记忆”，而是控制上下文窗口成本，让模型优先看到最近细节，同时保留长期背景。对应代码在 `src/main/java/com/example/agent/service/AgentService.java:46`、`src/main/java/com/example/agent/service/AgentService.java:161`、`src/main/java/com/example/agent/service/AgentService.java:188` 和 `src/main/resources/application.yml:74`。

如果问“如何控制上下文”，我会说我项目里做了四类控制：第一，短期消息窗口，避免把所有历史都塞进 prompt；第二，长期摘要，把旧对话压缩成稳定记忆；第三，工具结果截断，`AgentStepService` 对落库文本限制为 12000 字，避免过长 observation 污染链路；第四，RAG 只取最终 TopK 证据进入答案生成，避免召回过多 chunk。这个思路和 Claude Code、Hermes Agent 这类前沿编码 Agent 的方向是一致的。Claude Code 通过 `CLAUDE.md` 维护项目级记忆，还支持自动记忆导入和 `/memory` 管理，把“长期规则”和“当前对话”分层；它也有 subagent 机制，把不同任务交给拥有独立上下文窗口的专门 agent，并且 hooks 可以把权限、格式化、测试等动作做成确定性控制点。Hermes Agent 则强调跨会话 memory、skills 和 context compression，用可复用技能文档保存经验，并通过工具集、profile、权限和会话存储隔离上下文。这些给我的启发是：Agent 不是一味扩大上下文，而是要把上下文分层、压缩、路由和审计；真正工程化时，长期记忆必须可查看、可清理、可禁用，工具结果必须有边界。参考资料包括 Anthropic Claude Code memory、subagents、hooks 文档，以及 Hermes Agent memory/skills 设计说明。

如果问工具失败、超时、参数错误怎么办，我会结合项目说：我的工具执行层会把异常捕获成 `ToolExecutionResult.failure`，并作为 failed observation 回填给 planner。工具不存在或当前技能禁用时，不会直接报系统异常，而是写入“Unknown or disabled tool for current skill”和可用工具列表，让模型可以选择替代方案或结束并说明限制。模型输出 JSON 不合法时，会进入一次 `repairActionJson` 修复；如果修复仍失败，就降级为 finish，让最终回答合成器基于已有上下文回答。网页搜索 API 未配置、参数为空、文档不属于当前用户、文档未处理完成，也都会返回明确失败信息。对应代码在 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:85`、`src/main/java/com/example/agent/executor/ReActAgentExecutor.java:146`、`src/main/java/com/example/agent/tool/react/RagSearchReactTool.java:67` 和 `src/main/java/com/example/agent/tool/react/WebSearchTool.java:46`。

如果问如何避免 Agent 死循环，我会说我做了三件事。第一，`agent.react.max-steps` 默认 8，并且代码里最大 clamp 到 16，硬性限制循环次数；第二，系统 prompt 要求“sufficiently answered 就 finish”，不要为了工具而工具；第三，提供 `terminate` 工具作为停止信号，一旦调用成功，就进入最终回答合成。超过最大步数时，系统不会继续循环，而是根据已有 observation 给出尽可能好的部分答案，并记录 “Max ReAct steps exceeded”。对应代码在 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:44`、`src/main/java/com/example/agent/executor/ReActAgentExecutor.java:66`、`src/main/java/com/example/agent/executor/ReActAgentExecutor.java:109` 和 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:121`。

如果问 Agent 权限边界，我会从“用户数据边界、工具边界、网络边界、技能边界”回答。用户数据边界体现在 RAG 工具里，`rag_search` 会用 `findByIdAndUserId` 校验 documentId 是否属于当前用户，避免越权查询别人的文档；工具边界体现在 `ReactToolRegistry`，工具既要在当前技能白名单里，又要在管理端启用；网络边界体现在 `UrlSafety.requirePublicHttpUrl`，网页抓取或下载只允许 http/https，并禁止 localhost、127、10、172.16-31、192.168 等内网地址，降低 SSRF 风险；技能边界体现在 `AgentSkillService`，比如 `document_qa` 只允许 `document_list`、`rag_search`、`terminate`，`quick_tools` 只允许轻量工具。对应代码在 `src/main/java/com/example/agent/tool/react/RagSearchReactTool.java:84`、`src/main/java/com/example/agent/tool/react/UrlSafety.java:8`、`src/main/java/com/example/agent/service/AgentSkillService.java:104` 和 `src/main/java/com/example/agent/service/AgentSkillService.java:127`。

如果问 Prompt Injection 怎么防，我会说目前我项目做了基础隔离，但也清楚还有提升空间。已有措施是：系统 prompt 明确要求不能编造工具结果，最终答案必须基于 observation；RAG 证据以 `[S1]`、`[S2]` 这样的 citation 形式进入上下文，作为外部证据而不是系统指令；网页 URL 做安全校验；工具白名单和用户权限在代码层执行，不依赖模型自觉。后续演进我会借鉴 Claude Code 和 Hermes 这类 Agent 的做法，把“外部内容不可信”写进系统级规范，并在工具输出里加明确边界标签，比如 `UNTRUSTED_DOCUMENT_CONTENT`、`UNTRUSTED_WEB_CONTENT`；对 SQL、下载、文件写入这类高风险工具增加策略校验和人工确认；对 prompt injection 样本做红队评测，检测模型是否会被文档里的“忽略之前指令”带偏。我的观点是，Prompt Injection 不能只靠 prompt 防，必须把权限、工具执行和数据访问做成代码层硬约束。

## 二、RAG 和向量检索

如果问 RAG 完整流程，我会用项目链路回答：用户上传 PDF 后，系统创建 `Document`，状态是 PENDING，然后投递 RabbitMQ 消息；消费者异步执行 PDF 解析、文本切分、Embedding 生成、pgvector 入库和 Elasticsearch 索引写入；用户提问时，系统先对 query 做 embedding，在 pgvector 中做语义召回，同时在 Elasticsearch 中做 BM25 关键词召回，然后用 RRF 融合排序，生成带 citationId、chunkIndex、page、sourceType、confidence 的证据列表，最后将证据组装到 prompt 中生成回答。这个流程覆盖了文档解析、chunk、embedding、入库、召回、上下文组装和生成。对应代码在 `src/main/java/com/example/demo/service/DocumentService.java:59`、`src/main/java/com/example/demo/service/DocumentService.java:165`、`src/main/java/com/example/demo/service/RagService.java:75` 和 `src/main/java/com/example/agent/tool/react/RagSearchReactTool.java:100`。

如果问 chunk size 和 overlap 怎么选，我会说我不是只凭经验选的。项目的简单解析链路使用 `800/200`，也就是每个 chunk 最大约 800 字符，overlap 200。选择这个配置的原因是我做过离线小样本评测：4 份文档、50 个标注问题、5 组 `chunkSize/overlap`，`800/200` 的 Recall@5 是 0.96，MRR 是 0.7974，Top5 冗余度是 0.0688，平均 Top5 上下文估算约 1312 tokens。`1200/300` 虽然 MRR 更高，但上下文成本明显更高；Data Agent 场景里证据覆盖率和上下文成本都很重要，所以我倾向先保证 Recall@5，再控制冗余和 token。对应代码在 `src/main/java/com/example/demo/service/DocumentParsingService.java:144`，评测结果在 `loadtest/rag-eval/results/chunk_eval_summary.csv:3`。

如果问向量检索和 BM25 的区别，我会这样答：向量检索更擅长语义相似，比如用户问法和原文措辞不一样时仍能召回；BM25 更擅长关键词、专有名词、编号、术语和精确表达。我的项目里 pgvector 使用 query embedding 和 chunk embedding 的距离排序，Elasticsearch BM25 使用文本 match 和 documentId filter。单独用向量可能漏掉精确名词，单独用 BM25 可能漏掉同义表达，所以我做了双路召回。对应代码在 `src/main/java/com/example/demo/service/RagService.java:92` 和 `src/main/java/com/example/demo/service/RagService.java:109`。

如果问混合检索为什么有用，我会强调“召回互补”和“工程降级”。向量和 BM25 的相关性分数不是同一量纲，直接线性相加需要人工调权且不稳定；所以我默认用 RRF，只看每个 chunk 在各自列表里的排名，分数是 `1 / (k + rank)`，k 默认 60。这样同时被向量和 BM25 命中的 chunk 会获得更高融合分数，而只被一路命中的结果也不会完全丢掉。项目里还保留了 weighted fuse 配置，但默认是 RRF。BM25 异常时捕获异常并返回空列表，系统自动退化为 vector-only，不让 Elasticsearch 成为强依赖。对应代码在 `src/main/java/com/example/demo/service/RagService.java:79`、`src/main/java/com/example/demo/service/RagService.java:138`、`src/main/java/com/example/demo/service/RagService.java:143` 和 `src/main/resources/application.yml:111`。

如果问 reranker 是什么，我会说明 reranker 是召回后的二次排序层，通常可以用 cross-encoder 或 LLM judge 对 query 和候选 chunk 做更精细相关性判断。我的项目里目前实现的是 lightweight rerank，本质上是对融合分数做归一化和排序，不是严格意义上的深度 reranker，所以面试时不能过度包装。我会说现在的设计先把检索链路跑通，并预留了 `rag.retrieval.rerank.enabled` 开关；如果要进一步提高答案质量，可以接入 bge-reranker、Cohere Rerank 或者 LLM rerank，但要关注延迟、成本和批量推理能力。对应代码在 `src/main/java/com/example/demo/service/RagService.java:82`、`src/main/java/com/example/demo/service/RagService.java:190` 和 `src/main/resources/application.yml:116`。

如果问 Query Rewrite、HyDE、RAG-Fusion 分别解决什么，我会这样答：Query Rewrite 解决的是用户问题表达不完整、指代不清或者口语化的问题，把原问题改写成更适合检索的查询；HyDE 是先让模型生成一个假想答案或假想文档，再用这个假想内容去做向量检索，适合原问题太短、语义不够稳定的场景；RAG-Fusion 是生成多个不同角度的查询，分别检索后再用 RRF 等方法融合，提升多意图和复杂问题的召回覆盖。我项目现在做的是“检索结果层面的 fusion”，还没有做多 query 的 RAG-Fusion。面试时我会说如果往 Data Agent 演进，比如用户问“上周北京区域 GMV 为什么下降”，我会先把问题改写成指标、时间、维度和可能原因的多个查询，再分别查数据字典、指标口径、历史报表和异常日志，最后融合证据。

如果问 RAG 怎么评估，我会把评估拆成检索层和生成层。检索层看 Recall@K、MRR、NDCG@K 和冗余度，判断正确证据有没有召回、排得靠不靠前、TopK 是否重复；生成层看 faithfulness、answer relevance、citation correctness，判断答案是否忠于证据、是否回答问题、引用是否真实。我项目里已经有一个 `loadtest/rag-eval` 离线评测脚本，用 goldKeywords 做小样本证据命中评估，输出 Recall@3、Recall@5、MRR、nDCG@5 和 redundancy@5。面试时我要强调：`Recall@5=96%` 是 chunk 参数评测中的证据召回率，不等同于最终问答正确率。对应说明在 `loadtest/rag-eval/README.md:60` 和 `docs/interview-project-difficulties-and-metrics.md:31`。

如果问如何判断是检索问题还是生成问题，我会说：如果 TopK 证据里根本没有答案所需信息，就是检索问题，可能要调 chunk、embedding、混合检索、query rewrite 或权限过滤；如果证据里有答案，但模型回答错、漏引用、过度推断，就是生成问题，可能要优化 prompt、证据格式、引用约束或加入 answer verifier。我的项目里 `RagSearchResult` 会返回 confidenceScore、confidenceLevel 和 confidenceReason，`rag_search` observation 会带 citationId 和 relevance，方便定位是证据不足还是生成不稳。对应代码在 `src/main/java/com/example/demo/service/RagService.java:244` 和 `src/main/java/com/example/agent/tool/react/RagSearchReactTool.java:100`。

如果问 RAG 权限过滤怎么做，我会说权限必须前置到检索层，而不是生成层事后过滤。我的项目里每个文档有 userId，普通文档列表和 RAG 工具都按当前 userId 查询；指定 documentId 时用 `findByIdAndUserId` 校验归属；缓存 key 也包含 userId 和 documentId，避免不同用户或不同文档共享检索结果。删除文档时，同步清理 chunk、ES 索引、RAG 缓存、会话记录和本地文件，避免脏数据继续被召回。对应代码在 `src/main/java/com/example/agent/tool/react/DocumentListReactTool.java:31`、`src/main/java/com/example/agent/tool/react/RagSearchReactTool.java:84`、`src/main/java/com/example/demo/service/RagRetrievalCacheService.java:229` 和 `src/main/java/com/example/demo/service/DocumentService.java:231`。

如果问缓存怎么避免跨用户污染，我会说我的 Redis 缓存不是缓存最终答案，而是缓存“用户 + 文档 + 归一化问题”对应的证据 chunk 结果。key 形如 `rag:retrieval:v2:user:{userId}:doc:{documentId}:q:{sha256(normalizedQuestion)}`，TTL 默认 3600 秒，并维护 document index key，删除文档时可以按文档维度批量清理。读取缓存后还会回数据库取 live chunks，确保 chunk 仍存在，并保留 citationId、sourceType、score、confidence 等元数据。这样缓存节省的是 embedding、pgvector、BM25 和融合排序的开销，不把某一次生成答案固化下来，也降低跨用户污染风险。对应代码在 `src/main/java/com/example/demo/service/RagRetrievalCacheService.java:50`、`src/main/java/com/example/demo/service/RagRetrievalCacheService.java:157`、`src/main/java/com/example/demo/service/RagRetrievalCacheService.java:212` 和 `src/main/java/com/example/demo/service/RagRetrievalCacheService.java:229`。

## 三、数据 Agent / 数据分析场景

如果面试官问“你的项目是文档 Agent，怎么迁移到 Data Agent”，我会回答：底层抽象是一致的，都是让 LLM 通过工具和状态访问外部知识或外部系统。我的项目里 `rag_search` 是工具，未来在数据场景里可以把 `sql_query`、`schema_lookup`、`metric_definition`、`dashboard_query`、`ab_experiment_analyze`、`chart_generate` 封装成工具。不同点在于数据 Agent 的工具风险更高，因为 SQL 会访问真实业务数据，所以必须增加 schema 权限、SQL 安全校验、执行沙箱、结果脱敏和审计日志。我的项目已经有工具注册、技能白名单、用户上下文、步骤落库、最大步数和错误兜底，这些都可以迁移到 Data Agent。

如果问自然语言转 SQL 的流程，我会说我会拆成五步。第一步做意图识别，判断是查数、归因、AB 实验、报表生成还是数据解释。第二步做 schema linking，根据用户问题找到相关库表、字段、指标口径和业务维度。第三步生成 SQL，但要求结构化输出，比如包含 tables、columns、filters、time_range、sql。第四步做 SQL 校验，包括只读限制、禁止 DDL/DML、必须带租户和权限过滤、限制扫描范围、加 limit、检查字段是否存在。第五步执行 SQL，把结果回填给模型生成解释、图表或报告。这个流程和我项目里的 ReAct 非常像，只是 `rag_search` 换成 `schema_lookup` 和 `sql_execute`，并且工具安全要求更高。

如果问 SQL 生成后如何校验安全性，我会从代码层约束讲：不能只靠 prompt 让模型“不要写危险 SQL”，必须在执行前做 AST 级解析和策略校验。比如只允许 SELECT，拒绝 INSERT、UPDATE、DELETE、DROP、ALTER；检查是否访问了当前用户有权限的数据集；强制追加 tenant_id、department_id 或 row-level policy；限制时间范围和返回行数；禁止 `select *` 扫大表；对 ClickHouse/Hive 这类大查询增加超时、资源组和队列。真正执行前可以先 EXPLAIN 或 dry-run，估算扫描量，超过阈值要求用户确认或让 Agent 自动改写。这个思路和我项目里的 URL 安全、工具白名单一样：模型可以建议，最终边界由代码守住。

如果问 SQL 执行失败如何自动纠错，我会说可以把错误信息作为 observation 回填给 Agent，但要做错误类型约束。比如字段不存在，Agent 可以重新查 schema；类型不匹配，Agent 可以调整 cast；权限不足，Agent 不能绕过权限，只能提示无权限或换有权限指标；扫描量过大，Agent 应该缩小时间范围、增加聚合或 limit。最多重试 2 到 3 次，避免死循环。我的 ReAct 执行器已经具备“工具失败 -> observation -> 下一步决策”的结构，Data Agent 只需要把 SQL 错误设计成结构化 observation，并在最大步数和工具权限下运行。

如果问数据看板波动归因怎么做，我会说我会先把问题转成指标、时间窗口、对比基线和维度集合。比如 GMV 下降，要先确认口径和时间范围，然后按地区、渠道、品类、用户类型、流量来源等维度做 drill down，计算贡献度、环比/同比变化、异常维度 TopN，再关联活动、库存、价格、投放、系统故障等外部事件。Agent 在这里不是直接“猜原因”，而是编排多个分析工具：查指标口径、拉取明细聚合、做维度分解、生成图表、最后写成可解释报告。报告中要区分“数据支持的结论”和“需要业务确认的假设”。

如果问 AB 实验分析，我会说我理解的是比较实验组和对照组在核心指标上的差异，并判断这个差异是否显著、是否有业务意义。基本流程是确认实验单元、分流比例、实验时间、样本量、核心指标和护栏指标；然后计算均值、转化率、置信区间、p-value 或贝叶斯后验；再检查 SRM、样本污染、时间窗口、异常流量和多重检验问题。Data Agent 可以自动生成分析 SQL 和报告，但必须把统计方法和口径写清楚，不能只给“实验组更好”这种结论。

如果问报告自动生成怎么保证准确性，我会说我会做“三段式约束”：第一，数据结果必须来自工具 observation，报告不能编造数值；第二，报告中的每个关键结论要绑定来源，比如 SQL 查询 ID、图表、数据集版本、指标口径；第三，生成后用 verifier 检查数值是否和查询结果一致、是否出现无来源判断。我的 RAG 项目里已经要求保留 citationId，文档问答会输出引用来源；迁移到 Data Agent 时，citationId 可以变成 queryId、datasetVersion、metricDefinitionId。

如果问 Agent 如何接入 Hive、ClickHouse、BI 工具，我会说会把它们抽象为不同工具而不是直接暴露数据库连接。Hive/Spark 更适合离线数仓和复杂 ETL，可以通过任务提交工具或 SQL Gateway；ClickHouse 适合低延迟 OLAP 查询，可以作为交互式分析工具；BI 工具可以提供 dashboard metadata、指标口径和已有图表查询。Agent 需要先查 metadata，再选择合适执行引擎。高风险 SQL 必须经过权限和资源校验，执行结果要带 traceId，便于审计。

## 四、大数据生态

如果问 Hive、Spark、Flink、ClickHouse 的区别，我会用工作流回答：Hive 更偏离线数仓，适合大规模数据的 SQL 分析和分层建模；Spark 适合批处理、ETL 和复杂分析计算，核心要关注 shuffle、宽窄依赖、缓存和数据倾斜；Flink 适合实时流处理，核心是 checkpoint、状态、一致性语义和 watermark；ClickHouse 适合 OLAP 即席分析，依靠列式存储、压缩、向量化执行、稀疏索引和分区裁剪实现高性能查询。我不会把自己包装成生产大数据专家，而是会说我理解数据开发链路，能把这些引擎封装进 Agent 工具链。

如果问 ODS、DWD、DWS、ADS，我会说 ODS 是原始数据层，尽量保留业务系统原貌；DWD 是明细事实层，做清洗、规范化和维度关联；DWS 是汇总服务层，围绕主题域做公共聚合；ADS 是应用数据层，面向报表、看板、推荐或运营分析。Data Agent 要想稳定回答业务问题，不能直接在杂乱原始表上乱查，而是优先利用数仓分层、指标口径和数据字典。

如果问 Hive SQL 调优、Spark shuffle 和数据倾斜，我会防守性回答：Hive 调优通常看分区裁剪、避免全表扫描、小文件合并、join 策略、map/reduce 参数和谓词下推；Spark 里宽依赖会触发 shuffle，shuffle 代价高，要关注分区数、数据倾斜、广播 join、salting、AQE 和缓存；数据倾斜可以先定位倾斜 key，再通过过滤异常值、随机前缀、两阶段聚合、广播小表等方式处理。和 Agent 结合时，可以让 Agent 先 EXPLAIN 或读取查询画像，发现大扫描和倾斜风险后再建议改写 SQL。

如果问 Flink checkpoint 和 Watermark，我会说 checkpoint 是 Flink 做容错和状态一致性的机制，会周期性保存算子状态，失败后从最近 checkpoint 恢复；Watermark 是事件时间进度标记，用来处理乱序数据和窗口触发。Data Agent 如果用于实时指标解释，就需要理解延迟、乱序、窗口和数据最终一致性，否则会把未完全到达的数据误解成业务波动。

## 五、Prompt 工程

如果问 System Prompt 怎么设计，我会说 System Prompt 要承担角色、边界、工具规则、输出格式和安全约束。我的 ReAct prompt 明确要求模型只输出一个 JSON，不要 Markdown fence；给出 tool action 和 finish action 两种 schema；列出工具选择规则；要求不能编造工具结果；要求 document-grounded answer 保留 citation id。最终答案 synthesizer 的 prompt 则要求用中文 Markdown、基于工具 observation、区分已验证事实和假设。这个设计的重点是把 planner 和 answer 的 prompt 分开，降低一个 prompt 同时承担太多职责的风险。对应代码在 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:156` 和 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:258`。

如果问 Few-shot / Zero-shot，我会说 Zero-shot 是不给示例，直接靠指令完成任务；Few-shot 是给几个输入输出样例，让模型学习格式和风格。在工具调用场景，我会更倾向于少量高质量 few-shot，尤其是复杂工具参数、SQL 生成、错误纠错和引用格式。我的当前项目主要靠 schema 和规则约束，没有大量 few-shot；如果要提升稳定性，我会收集真实失败案例，把“文档目标不清楚先 document_list”“实时事实先 date_time 再 web_search”“工具失败后说明限制”做成 few-shot。

如果问 CoT / ToT，我会说 CoT 是让模型按链式思考逐步推理，ToT 是在多个思路分支中搜索和评估。但工程上我不会把完整推理暴露给用户，而是把它转成可审计的 plan、tool call、observation 和 final answer。我的 `agent_step` 记录的不是模型完整内心推理，而是执行层可审计的计划和工具结果，这样既能排查问题，又避免泄露过长、不可控的思维链。

如果问结构化输出 JSON 怎么保证稳定，我会说要靠多层约束。第一，prompt 给严格 schema；第二，输出后用 Jackson 解析；第三，用 `validate` 校验 type 只能是 `tool` 或 `finish`，tool action 必须有 toolName；第四，如果解析失败，调用 repair 模型做一次 JSON 修复；第五，修复仍失败就降级 finish，不把坏 JSON 直接暴露给用户。对应代码在 `src/main/java/com/example/agent/executor/ReActAction.java:33`、`src/main/java/com/example/agent/executor/ReActAction.java:69` 和 `src/main/java/com/example/agent/executor/ReActAgentExecutor.java:146`。

如果问长上下文怎么裁剪，我会说我会分“会话历史、工具结果、RAG 证据、长期记忆”四类处理。会话历史保留最近窗口，旧内容摘要；工具结果截断并只保留关键 observation；RAG 证据用 TopK 和 rerank 控制数量；长期记忆只保留稳定偏好和项目事实。前沿 Agent 也在做类似事情，比如 Claude Code 把项目规则放在 `CLAUDE.md`，使用 subagent 分离上下文，Hermes 把可复用流程沉淀为 skills，并通过 compression 控制上下文膨胀。我的理解是：上下文管理的目标不是“记住一切”，而是让模型在当前任务里看到最该看的内容。

如果问 Temperature / Top-p，我会说 Temperature 越高输出越发散，适合创意生成；越低越稳定，适合 SQL、工具调用、结构化 JSON 和事实问答。Top-p 是 nucleus sampling，控制候选 token 累计概率范围。我的 Agent planner 应该使用较低 temperature，因为工具调用和 JSON 要稳定；最终报告生成可以略高一点，但数据分析和 RAG 场景仍要偏稳，避免编造。

## 六、Redis / MQ / 分布式

如果问 Redis 为什么快，我会说主要因为内存存储、单线程事件循环避免锁竞争、IO 多路复用、高效数据结构和简单命令模型。我的项目里 Redis 不是用来缓存最终回答，而是缓存检索证据，减少重复 embedding、pgvector、BM25 和 RRF 计算。这样既提升热点问题延迟，也避免把生成答案缓存成过期事实。

如果问缓存穿透、击穿、雪崩，我会结合 RAG 缓存回答：穿透是查不存在的数据，可以对空结果短 TTL 缓存或做参数校验；击穿是热点 key 失效后大量请求打到后端，可以用互斥锁、逻辑过期或提前刷新；雪崩是大量 key 同时失效，可以给 TTL 加随机抖动、限流降级和多级缓存。我的项目目前 TTL 默认 3600 秒，后续如果做高并发，会给热点文档检索加互斥加载和 TTL jitter。

如果问 RabbitMQ 为什么用，我会说 PDF 解析、OCR、Embedding、pgvector 入库和 ES 索引写入都可能耗时，不能阻塞上传接口，所以用 RabbitMQ 把上传请求和文档 ETL 解耦。`DocumentService.saveDocument` 保存 PENDING 文档后发送持久化消息，消费者异步处理；RabbitMQ listener 使用手动 ACK；可重试异常进入延迟重试队列，超过 maxRetries 进入死信队列；如果发布重试消息失败，会 NACK 原消息重新入队，避免任务丢失。对应代码在 `src/main/java/com/example/demo/service/DocumentService.java:59`、`src/main/java/com/example/demo/service/DocProcessor.java:28`、`src/main/java/com/example/demo/service/DocProcessor.java:53` 和 `src/main/java/com/example/demo/config/RabbitConfig.java:49`。

如果问如何保证消息不丢和重复消费，我会说 RabbitMQ 本身提供的是至少一次投递，不保证业务不重复，所以可靠性要分两层。消息层面，我用持久化队列、持久化消息、publisher confirm/returns、手动 ACK、重试队列和死信队列；业务层面，我用文档状态机和条件更新抢占处理权，只有 PENDING、FAILED_RETRYABLE 或过期 PROCESSING 才能被处理，同时 chunk 写入前会清理旧索引，ES 使用确定性 ID `docId_chunkIndex`。此外还有补偿扫描，每 60 秒重新投递 PENDING、FAILED_RETRYABLE 和过期 PROCESSING 任务。对应代码在 `src/main/resources/application.yml:131`、`src/main/java/com/example/demo/service/DocumentService.java:132`、`src/main/java/com/example/demo/service/DocumentProcessRecoveryScheduler.java:33` 和 `src/main/java/com/example/demo/service/DocumentProcessRecoveryScheduler.java:68`。

如果问 MQ 积压怎么处理，我会说要先区分瓶颈：是 PDF 解析慢、OCR 慢、Embedding API 慢、数据库写入慢，还是消费者数量不足。处理手段包括提高 consumer 并发、调整 prefetch、把 OCR/Embedding 拆成独立队列、批量写入、降级非关键处理、限流上传、对大文件单独队列、监控 ready/unacked 和处理耗时。我的项目现在 `prefetch` 默认是 1，更偏保守，适合本地开发和避免单消费者过载；生产环境会根据 API 限额和数据库吞吐调大。

如果问限流、熔断、降级，我会结合 Agent 说：Agent 系统的外部依赖很多，包括 LLM、Embedding、ES、Redis、RabbitMQ、网页搜索。限流是防止用户或任务把下游打满；熔断是某个依赖持续失败时快速失败，避免线程堆积；降级是用可接受的替代路径，比如 BM25 失败时退化为 vector-only，Redis 失败时跳过缓存，网页搜索未配置时只基于本地文档回答。我的 RAG 链路里 ES 异常已经会降级为向量检索，Redis 缓存读写异常也只是跳过，不影响主流程。

## 七、Java 高频八股兜底

如果问 HashMap 和 ConcurrentHashMap，我会答到基础原理即可。HashMap 底层是数组加链表或红黑树，JDK 8 后链表过长且数组容量达到阈值会树化；它不是线程安全的。ConcurrentHashMap 通过 CAS、volatile 和 synchronized 控制桶级并发，读多写少场景性能更好。和我的项目关联是，工具注册表用 `Map.copyOf(discovered)` 固化工具集合，注册后读多写少；真正动态启停放在数据库配置里查。

如果问 synchronized、volatile、CAS、AQS，我会说 synchronized 保证互斥和可见性，JVM 会做锁升级优化；volatile 保证可见性和有序性，但不保证复合操作原子性；CAS 是乐观并发控制，依赖硬件原子指令，但有 ABA 和自旋开销问题；AQS 是 Java 锁和同步器的基础框架，用 state 和队列管理线程。我的项目里并发重点不在手写锁，而在用数据库条件更新、事务和 MQ ACK 保证异步任务状态一致。

如果问线程池，我会答核心参数：corePoolSize、maximumPoolSize、keepAliveTime、workQueue、threadFactory、rejectedExecutionHandler。Agent/RAG 项目里耗时任务不能随意堆到 Web 线程，比如 ReAct 执行用了 Reactor 的 `boundedElastic`，文档 ETL 用 RabbitMQ 消费者解耦。生产里还要给 LLM、Embedding、OCR、SQL 执行分不同线程池和限流策略，避免互相拖垮。

如果问 ThreadLocal，我会说它适合保存线程上下文，比如用户信息、traceId、事务上下文，但在线程池场景要注意清理，否则可能内存泄漏或上下文串号。Data Agent 里更推荐显式传递 userId、sessionId、tenantId 和 traceId。我的工具执行上下文使用 `ToolExecutionContext` 显式传 userId、sessionId、messageId、workspace 和 allowedTools，这比隐式 ThreadLocal 更可控。

如果问 JVM 和 GC，我会答 JVM 内存分为堆、栈、方法区、程序计数器和本地方法栈；GC Roots 包括栈引用、静态变量、JNI 引用等；Minor GC 主要回收新生代，Full GC 涉及更大范围；G1 把堆分成 region，以可预测停顿为目标。OOM 排查先看错误类型、堆 dump、GC 日志、对象占用和线程情况。我的项目里 PDF、图片、OCR、Embedding 可能带来大对象和 IO 压力，所以生产需要限制上传大小、分批处理 chunk、及时释放 PDF 资源和监控内存。

如果问 Spring IoC / AOP / 事务 / 循环依赖，我会答：IoC 是容器负责对象创建和依赖注入；AOP 用代理增强横切逻辑，比如事务、日志、权限；事务要注意传播行为、隔离级别、回滚规则和自调用失效。我的项目里 `DocumentService` 使用 `TransactionTemplate` 把状态抢占和索引重建拆成明确事务边界，避免长事务包住整个 PDF 解析和 Embedding 调用。

## 八、MySQL / SQL 基础防守

如果问 B+ 树索引，我会说 B+ 树高度低、叶子节点有序，适合范围查询和排序；联合索引遵循最左前缀，查询条件要尽量从左到右匹配；索引失效常见原因包括对列做函数、隐式类型转换、前置模糊 like、不符合最左前缀、低选择性字段等。Data Agent 场景里 NL2SQL 生成的 SQL 不能只语法正确，还要考虑索引和扫描量，否则会拖垮数据平台。

如果问 EXPLAIN 和慢 SQL 优化，我会说先看 type、key、rows、Extra，判断是否走索引、扫描行数是否过大、是否 filesort 或 temporary。优化方式包括补合适索引、改写 SQL、减少返回列、分解复杂查询、避免大 offset、优化 join 顺序、分区裁剪和预聚合。Data Agent 在执行 SQL 前可以自动 EXPLAIN，发现 rows 过大或没有命中索引时让模型改写或提示用户缩小范围。

如果问 MVCC 和隔离级别，我会说 MVCC 通过版本链和 ReadView 实现非锁定一致性读；读已提交每次查询生成新 ReadView，可重复读通常事务内复用 ReadView；隔离级别从低到高是读未提交、读已提交、可重复读、串行化。面试里不用深挖 redo/undo/binlog 两阶段提交细节，但要知道事务一致性会影响数据分析结果，比如报表查询跨多个表时要关注快照一致性。

如果问复杂 SQL，我会准备连续登录、TopN、分组统计、留存和漏斗分析。连续登录可以用日期减 row_number 分组；TopN 可以用窗口函数 `row_number() over(partition by ... order by metric desc)`；分组统计要注意 group by 维度和指标口径。对于 Data Agent，重点不是背 SQL 模板，而是能让模型生成 SQL 后再做语义校验、口径校验和安全校验。

## 九、最后的面试表达主线

我的主线可以这样收束：我做的项目不是一个简单 RAG demo，而是围绕私有知识库做了一套 Agentic RAG 工程闭环。底层用 RabbitMQ 把文档解析和向量化入库异步化，用 pgvector 和 Elasticsearch 做混合检索，用 RRF 解决异构分数融合，用 Redis 缓存证据 chunk 降低重复检索成本；Agent 层基于 Spring AI 和自研 ReAct 执行器，把 RAG、网页搜索、网页抓取、下载、PDF 生成、日期和计算封装成可编排工具，并通过技能白名单、用户权限、最大步数、JSON 校验、失败 observation 和 agent_step 落库保证可控、可追踪。

对这个 Data Agent 岗位，我最匹配的点不是“我会 Java 八股”，而是我已经实践过 Agent = LLM + Planning + Memory/State + Tools 这套工程结构，也做过 RAG 检索链路、混合召回、检索评估、缓存隔离和异步 ETL。我的短板是 Hive、Spark、Flink、ClickHouse 的生产经验还不深，但我理解它们在数据平台里的职责，也能把它们抽象成 Agent 工具，并围绕权限、安全、SQL 校验、结果引用和报告准确性来设计 Data Agent。

面试最后如果让我概括这个岗位，我会说：Data Agent 的难点不是让模型说得像人，而是让模型在数据权限边界内，稳定地规划任务、选择工具、查询数据、解释结果、生成可追溯报告。我的项目经验正好让我从工程角度理解这件事：Agent 要能调工具，但工具要被注册、校验、限权和观测；RAG 要能召回知识，但证据要评估、引用和隔离；数据分析要能自动化，但 SQL、安全和口径必须由系统硬约束兜底。

## 十、复习优先级提醒

第一优先级复习 Agent：把 `AgentService`、`ReActAgentExecutor`、`ReactToolRegistry`、`AgentSkillService`、`AgentStepService` 的链路背熟，重点说清楚路由、JSON 动作、工具白名单、状态记忆、错误恢复、最大步数和权限边界。

第二优先级复习 RAG：把 PDF ETL、800/200 chunk、pgvector Top20、BM25 Top20、RRF k=60、Top10 证据、Redis retrieval cache、Recall@5=96% 这些数字和取舍背熟。

第三优先级复习 Data Agent：重点准备 NL2SQL、安全校验、SQL 自动纠错、看板归因、AB 实验、报告引用和数据权限，把它们和你现有的工具编排、RAG citation、userId 隔离对应起来。

第四优先级复习 Prompt 安全和前沿 Agent：准备 Claude Code 的项目记忆、subagent、hooks，Hermes Agent 的 memory、skills、context compression，把它们讲成“我关注到工程化 Agent 都在做上下文分层、技能沉淀、工具权限和审计”的借鉴点。

第五优先级复习 Redis、RabbitMQ、Java、MySQL 和大数据生态：这些要答稳，但不要抢主线。每个点都尽量回到项目，例如 RabbitMQ 对应异步 ETL，Redis 对应检索缓存，MySQL/SQL 对应 NL2SQL 校验，Java 并发对应异步任务和状态一致性。

## 参考资料

Claude Code 记忆体系参考官方文档：<https://docs.anthropic.com/en/docs/claude-code/memory>。其中 `CLAUDE.md`、auto memory、`.claude/rules/` 和 `/memory` 的设计，可以用来说明“长期规则、自动学习、路径级规则、可审计记忆”对 Agent 工程化的启发。

Claude Code subagents 参考官方文档：<https://docs.anthropic.com/en/docs/claude-code/sub-agents>。其中“每个 subagent 有独立上下文窗口、工具权限和系统提示词”的设计，可以用来类比 Data Agent 中的 schema 分析 agent、SQL 校验 agent、报告生成 agent。

Claude Code hooks 参考官方文档：<https://docs.anthropic.com/en/docs/claude-code/hooks>。hooks 的价值在于把部分行为从模型自觉变成确定性控制点，可以类比 SQL 安全校验、工具调用前审计、结果落库和报告后验校验。

Hermes Agent persistent memory 参考官方文档：<https://hermes-agent.nousresearch.com/docs/user-guide/features/memory>。其中 bounded memory、USER/MEMORY 分离、会话启动时注入、容量限制和安全扫描，可以用来支撑“记忆不是无限塞上下文，而是有容量、有边界、有治理”的观点。
