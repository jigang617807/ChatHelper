# 基于 ReAct Agentic RAG 的私有知识库智能体系统面试梳理

## 一、项目定位

这个项目不是一个简单的“大模型问答系统”，而是一个围绕私有文档知识库构建的 Agentic RAG 智能体系统。

系统支持 PDF 文档上传、异步解析、文本切分、Embedding 向量化、pgvector 入库、Elasticsearch 关键词索引、Redis 检索缓存、混合检索问答，以及基于 ReAct 的 Agent 工具编排。

一句话介绍：

> 我做的是一个基于 Spring Boot 和 Spring AI 的私有知识库智能体系统。底层通过 RabbitMQ 解耦文档 ETL，用 PostgreSQL/pgvector 和 Elasticsearch 做多路召回，用 RRF 做融合排序，用 Redis 做热点检索缓存；在 Agent 层基于 Spring AI ChatModel 自研 ReAct 执行器，将 RAG 检索、网页搜索、网页抓取、资源下载、PDF 生成等能力封装成本地工具，实现可控、可追踪的复杂任务执行。

## 二、技术栈

- Java 17
- Spring Boot 3
- Spring AI
- DeepSeek ChatModel
- PostgreSQL + pgvector
- Elasticsearch
- Redis
- RabbitMQ
- Thymeleaf
- WebFlux / SSE
- PDFBox

## 三、核心业务链路

### 1. 文档上传链路

用户上传 PDF 后，上传接口不直接解析文件，只做轻量操作：

```text
保存 PDF 文件
保存 document 元数据
document 状态设置为 PENDING
投递 docId 到 RabbitMQ
立即返回页面
```

这样可以避免大文件解析、Embedding 调用、数据库写入阻塞上传接口。

目前已经支持多文件上传：

```text
前端 input 使用 multiple
后端接收 MultipartFile[] files
每个 PDF 单独保存
每个 PDF 单独创建 document 记录
每个 document 单独投递 RabbitMQ 处理任务
```

磁盘文件名使用 `userId + UUID + 原始文件名`，避免同名文件覆盖；数据库展示标题保留用户上传的原始文件名。

### 2. 异步文档处理链路

RabbitMQ 消费者拿到 docId 后，执行完整 ETL：

```text
根据 docId 查询 document
抢占 PROCESSING 状态
清理旧 PG chunk、ES 索引、Redis 缓存
PDFBox 解析 PDF 文本
TextSplitter 按重叠窗口切分文本
调用 Embedding 接口生成向量
写入 PostgreSQL document_chunk
写入 Elasticsearch document_chunk 索引
更新 document 状态为 COMPLETED
```

这个设计把上传请求和重型文档处理解耦，提升接口响应速度，也方便后台重试和补偿。

## 四、RabbitMQ 可靠性设计

### 1. 队列设计

项目里不是只用了一个普通队列，而是设计了三类队列：

```text
主队列：document.process.queue
重试队列：document.process.retry.queue
死信队列：document.process.dead.queue
```

正常流程：

```text
document.process.exchange
  -> document.process.queue
  -> DocProcessor 消费
```

可重试失败流程：

```text
主队列消费失败
  -> 投递到 retry exchange
  -> 进入 retry queue
  -> 等待 TTL
  -> 通过 DLX 回到主队列
  -> 再次消费
```

超过最大重试次数：

```text
标记 document 为 FAILED
投递到 dead exchange
进入 dead queue
```

### 2. 手动 ACK

项目开启了手动 ACK：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual
```

手动 ACK 的意义是：消费者收到消息不代表任务完成，只有真正处理成功，或者消息已经安全转移到 retry/dead queue 后，才确认原消息。

处理逻辑：

```text
处理成功 -> basicAck
不可重试失败 -> 标记 FAILED -> basicAck
可重试失败且未超过最大次数 -> 投递 retry queue -> basicAck
投递 retry queue 失败 -> basicNack(requeue=true)
超过最大重试次数 -> 标记 FAILED -> 投递 dead queue -> basicAck
```

这里最关键的是：如果重试消息都没投递成功，就不 ACK 原消息，而是 NACK 并重新入队，避免任务丢失。

### 3. Publisher Confirm 和 Returns

生产端开启：

```yaml
publisher-confirm-type: correlated
publisher-returns: true
```

作用：

```text
publisher confirm：确认消息是否到达交换机
publisher returns：确认消息无法路由到队列时是否被返回
```

这样可以感知 MQ 生产端异常，例如交换机不可用、routingKey 错误、消息无法路由。

### 4. 定时补偿

极端情况下可能出现：

```text
document 已保存为 PENDING
但 MQ 消息发送失败
```

所以项目里有定时补偿任务，扫描：

```text
PENDING
FAILED_RETRYABLE
过期 PROCESSING
```

然后重新投递处理消息。

最终可靠性由多层共同保证：

```text
数据库任务状态
RabbitMQ 持久化队列
手动 ACK
重试队列
死信队列
Publisher Confirm
Returns
定时补偿扫描
```

## 五、RabbitMQ 幂等消费设计

幂等消费解决的问题是：

```text
同一个 docId 被重复投递怎么办？
消费者处理到一半宕机，重启后重复消费怎么办？
会不会重复写 chunk？
```

项目中主要通过三层实现。

### 1. document 状态机

文档状态包括：

```text
PENDING
PROCESSING
COMPLETED
FAILED_RETRYABLE
FAILED
```

消费者开始处理前，会通过条件更新抢占处理权：

```sql
update document
set status = 'PROCESSING'
where id = ?
and status in ('PENDING', 'FAILED_RETRYABLE')
```

如果更新行数为 0，说明这个任务已经被其他消费者处理，或者状态不允许处理，当前消息直接跳过。

如果文档已经是 `COMPLETED`，重复消息来了也直接返回，不重复处理。

### 2. 重建前清理旧数据

每次重新处理文档前，先按 docId 清理旧索引：

```text
删除 PostgreSQL 中 document_chunk
删除 Elasticsearch 中 documentId 对应索引
删除 Redis 中该文档检索缓存
```

然后再重新解析、切分、Embedding、写 PG 和 ES。

这样失败重试时不是在旧数据后面追加，而是先清理再重建。

### 3. 唯一约束与确定性 ID

PG 中对 chunk 增加唯一约束：

```text
(document_id, chunk_index)
```

ES 的文档 ID 使用确定性 ID：

```text
docId_chunkIndex
```

这样同一个文档同一个 chunk 重复写入时，不会生成重复数据。

面试总结：

> 我通过 document 状态机做消费抢占，通过处理前清理旧 PG/ES/Redis 数据避免重复追加，通过 PG 唯一约束和 ES 确定性 ID 保证重复投递下不会生成重复 chunk。

## 六、FAILED_RETRYABLE 和死信队列

### 1. FAILED_RETRYABLE 什么时候出现

`FAILED_RETRYABLE` 表示文档处理失败，但失败原因可能是临时性的，后续重试可能成功。

典型场景：

```text
Embedding 接口超时
Embedding 接口限流
ES 临时不可用
Redis 临时异常
数据库瞬时异常
网络波动
其他运行时异常
```

这些异常会标记为：

```text
FAILED_RETRYABLE
retryCount + 1
errorMessage 记录失败原因
```

同时投递 retry queue，延迟后重新消费。

### 2. 哪些不是 FAILED_RETRYABLE

不可重试异常会直接标记为 `FAILED`：

```text
PDF 文件不存在
PDF 文本为空
PDF 解析失败
document 记录不存在
```

这些重试一般也没意义。

### 3. 死信队列怎么消费

当前项目里，死信队列主要用于失败归档和人工排查，没有自动消费者。

超过最大重试次数后：

```text
document 状态标记为 FAILED
消息投递到 document.process.dead.queue
消息里带 docId、retryCount、errorMessage
```

死信队列的意义：

```text
避免坏消息无限重试
保留失败现场
方便人工排查
后续可以扩展告警或后台重新投递
```

## 七、消息积压处理

消息积压本质是：

```text
生产速度 > 消费速度
```

在本项目中可能原因包括：

```text
用户短时间批量上传 PDF
PDF 文件过大
Embedding 接口响应慢或限流
PG 写入慢
ES 写入慢
消费者实例数量不足
消费者单条任务耗时过长
```

排查指标：

```text
ready 消息数
unacked 消息数
publish rate
ack rate
consumer 数量
document 中 PENDING / PROCESSING / FAILED_RETRYABLE 数量
死信队列数量
```

处理方式：

```text
增加消费者实例
合理调整 listener concurrency
限制上传频率和单次上传数量
限制 Embedding 并发，保护下游服务
可重试异常进入 retry queue
不可重试异常直接 FAILED
超过最大重试次数进入 dead queue
必要时把文档级任务拆成 chunk 级任务提升并行度
```

### prefetch 的作用

`prefetch` 是消费者预取数量，表示一个消费者在未 ACK 前最多可以持有多少条消息。

项目中设置 `prefetch=1`，表示：

```text
每个消费者一次只拿一个文档任务
处理完 ACK 后再拿下一条
```

原因是文档解析任务较重、耗时不稳定。如果 prefetch 太大，一个消费者可能一次拿走多个大 PDF，导致 unacked 堆积、消费者负载不均，消费者宕机后也会有更多消息需要重新投递。

面试回答：

> 文档处理任务比较重，所以我更倾向于保持较小 prefetch，通过增加消费者实例提升吞吐，而不是让单个消费者一次囤积很多任务。

## 八、RAG 检索链路

### 1. 多路召回

项目不是单纯向量检索，而是混合检索：

```text
Query -> Embedding -> PostgreSQL/pgvector 语义召回
Query -> Elasticsearch BM25 关键词召回
两路结果 -> RRF 融合排序
取 finalTopK -> 拼接上下文 -> 大模型回答
```

pgvector 适合语义相似问题，例如：

```text
总结创新点
核心贡献是什么
这篇文章解决了什么问题
```

BM25 适合关键词精确匹配，例如：

```text
术语
编号
专有名词
具体概念
```

两者互补，提升召回稳定性。

### 2. RRF 融合

RRF 的思想是按排名融合，而不是直接比较原始分数。

公式：

```text
score = Σ 1 / (k + rank)
```

选择 RRF 的原因：

```text
向量相似度和 BM25 分数尺度不同
线性加权需要调权重
RRF 只依赖排名，更稳定
实现简单，适合多路召回融合
```

### 3. ES 异常兜底

ES 的 BM25 查询单独 try-catch。

如果 ES 查询失败：

```text
记录 warn 日志
返回 emptyList
RRF 融合 vectorHits + emptyList
最终退化为 pgvector-only
```

所以 ES 挂了不会导致整个 RAG 查询失败，只是检索质量有所下降。

当前项目是“异常兜底”，还没有做精细超时判断和熔断。后续可以加：

```text
ES client timeout
Resilience4j TimeLimiter
CircuitBreaker
Fallback
```

## 九、Redis 检索缓存

### 1. 缓存什么

Redis 缓存的不是完整答案，也不是 chunk 全文，而是一次 RAG 查询命中的 chunkIds。

真实查询缓存 key：

```text
rag:retrieval:v1:user:{userId}:doc:{documentId}:q:{questionHash}
```

value 是 JSON：

```json
{
  "userId": 1,
  "documentId": 10,
  "normalizedQuestion": "总结创新点",
  "chunkIds": [101, 105, 109],
  "createdAt": 1710000000000
}
```

问题会先做归一化：

```text
去掉首尾空格
多个空白合并成一个空格
转小写
```

然后做 SHA-256，避免长问题和特殊字符直接进入 Redis key。

### 2. 为什么要维护 index set

真实 key 里有 questionHash，文档删除或重建时不知道历史上用户问过哪些问题，也就不知道所有真实 key。

所以项目额外维护一个文档级索引 set：

```text
rag:retrieval:v1:index:user:{userId}:doc:{documentId}
```

这个 set 里保存该用户该文档下产生过的所有真实查询缓存 key。

每次写缓存时：

```text
SET 真实查询 key -> chunkIds JSON
SADD index set -> 真实查询 key
设置 TTL
```

删除或重建文档时：

```text
SMEMBERS index set 拿到所有真实 key
批量 DELETE 真实 key
DELETE index set
```

这样不需要线上使用 `KEYS rag:retrieval:*` 模糊扫描，避免阻塞 Redis。

### 3. v1 的含义

`v1` 是缓存结构版本号。

如果后续 value 结构变化，比如从只存 chunkIds 改成存 score、source、rank，可以改成：

```text
rag:retrieval:v2
```

新代码只读 v2，旧 v1 等 TTL 自动过期，避免新旧缓存结构冲突。

## 十、Agent 设计

### 1. Agent 范式

项目中的 Agent 是自研 ReAct 执行器，不是 LangGraph，也不是 Spring AI 现成的完整 Agent。

执行流程：

```text
用户问题
  -> AgentService 判断是否需要工具
  -> ReActAgentExecutor
  -> 模型输出 JSON action
  -> 后端解析 type / plan / toolName / arguments
  -> 后端校验工具白名单
  -> 执行本地工具
  -> 工具结果作为 observation 注入下一轮
  -> 模型继续判断下一步
  -> 直到 finish 或达到 maxSteps
```

模型必须输出：

```json
{
  "type": "tool",
  "plan": "这一步要做什么",
  "toolName": "rag_search",
  "arguments": {
    "question": "总结第一个文档的创新点",
    "documentId": 1,
    "topK": 5
  }
}
```

或者：

```json
{
  "type": "finish",
  "plan": "已经获得足够信息",
  "finalAnswer": "最终回答"
}
```

### 2. ReAct 控制能力

项目对 ReAct 做了流程级控制：

```text
最大步数控制
动作类型校验
工具白名单校验
参数解析
工具异常处理
observation 注入
agent_step 落库
```

最大步数默认 8，并在代码里限制到 1 到 16，防止无限循环。

当前还没有做严格业务状态机，例如：

```text
POLICY_FOUND -> HOTEL_FOUND -> PLAN_READY
```

所以对于“先查报销标准，再查酒店，最后规划”这种强依赖任务，目前主要靠 prompt 和 observation 约束。后续可以增加工具前置条件校验，没有 `POLICY_FOUND` 状态时拒绝 `hotel_search`。

### 3. 工具注册机制

每个本地工具实现统一接口：

```java
public interface ReactTool {
    String name();
    String description();
    String parameters();
    ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}
```

工具类加 `@Component` 后，Spring 会注册成 Bean。

`ReactToolRegistry` 构造函数声明：

```java
public ReactToolRegistry(List<ReactTool> tools)
```

Spring 会自动把容器里所有 `ReactTool` 类型的 Bean 注入进来，然后注册表按 `name()` 构建：

```text
Map<String, ReactTool>
```

所以新增工具只需要：

```text
实现 ReactTool
加 @Component
提供 name / description / parameters / execute
```

### 4. 大模型怎么知道工具

大模型并不是直接知道 Java Bean，而是 ReActExecutor 每轮构造 system prompt 时，把工具注册表中的工具说明拼进去：

```text
Available tools:
- name: rag_search
  description: Search relevant chunks from uploaded documents...
  parameters: {...}
```

模型根据这些工具说明输出 `toolName` 和 `arguments`。

后端再根据 toolName 在白名单里查找：

```text
找得到 -> 执行工具
找不到 -> 返回失败 observation
```

### 5. RAG 怎么封装成工具

RAG 工具名是：

```text
rag_search
```

它不是重新实现检索，而是调用已有：

```text
RagCachedRetrievalService
```

复用原来的：

```text
Redis 缓存
pgvector 向量召回
Elasticsearch BM25 召回
RRF 融合排序
```

同时还有：

```text
document_list
```

用于列出当前用户文档。比如用户问“总结第一个文档的创新点”，Agent 应该先调用 `document_list` 找到第一个文档的 documentId，再调用 `rag_search`。

### 6. agent_step 执行链路

每一步 Agent 执行都会落库到 `agent_step`：

```text
PLAN：模型计划
TOOL_CALL：工具调用参数
TOOL_RESULT：工具返回结果
FINAL：最终回答
ERROR：异常信息
```

核心字段：

```text
sessionId
messageId
stepIndex
stepType
toolName
toolArguments
toolResult
status
latencyMs
errorMessage
createdAt
```

意义：

```text
可追踪
可调试
可审计
可前端展示执行链路
```

## 十一、Spring AI 用到了哪些

项目中实际使用了：

```text
ChatModel
ChatClient
@Tool
MethodToolCallbackProvider
ToolCallbackProvider
ToolContext
```

其中：

```text
ChatModel：ReAct 执行器每一步调用大模型生成 JSON action
ChatClient：普通不需要工具的问题走流式回答
@Tool：早期/旁路工具封装方式
MethodToolCallbackProvider：把 @Tool 方法转成 ToolCallbackProvider
ToolContext：传递用户和会话上下文
```

没有直接使用：

```text
ToolCallingManager
Advisor
ToolCallAdvisor
LangGraph
LangChain4j
```

面试口径：

> Spring AI 提供了 ChatModel、ChatClient 和 Tool Calling 基础设施，但我项目里的 ReAct 编排是自己实现的。我用 ChatModel 调模型，用自定义 ReactTool 和 ReactToolRegistry 管理工具，用 ReActAgentExecutor 控制循环、observation、最大步数、工具错误和链路落库。

## 十二、流式与非流式

普通问题不需要工具调用时：

```text
ChatClient.stream()
SSE 流式返回
```

涉及 RAG、网页、下载、PDF、工具问题时：

```text
走 ReActAgentExecutor
多轮模型调用和工具调用
最终一次性返回 finalAnswer
```

原因是 ReAct 中间过程会产生 JSON action，这些不是给用户看的。如果流式直接输出，前端可能会把 JSON 渲染出来。

踩坑：

> 之前出现过最终返回 JSON 被前端直接展示的问题，后来修复为前端只展示 finalAnswer，中间 PLAN/TOOL_CALL/TOOL_RESULT 落库到 agent_step。

## 十三、上下文膨胀和 Lost in the middle

Lost in the middle 指大模型处理长上下文时，更容易关注开头和结尾，容易忽略中间关键信息。

当前项目的缓解方式：

```text
RAG finalTopK 限制
RRF 重排只保留高相关 chunk
web_search 只返回 title/url/snippet
web_scraping 限制 maxChars
ToolExecutionResult 截断长 observation
maxSteps 限制工具轮数
```

当前还没有完整的 observation 压缩器。

后续可以加：

```text
ObservationCompressor
结构化 facts
source/citation
working memory
map-reduce 网页摘要
```

## 十四、主要踩坑点

### 1. PDFBox 解析扫描件失败

PDFBox 只能提取文本型 PDF。如果 PDF 是扫描件或图片型 PDF，会出现：

```text
PDF text is empty
```

这不是上传失败，而是异步解析失败。后续需要 OCR 支持。

### 2. RabbitMQ 重复消费

消息可能重复投递，所以不能直接 append chunk。需要：

```text
状态机抢占
COMPLETED 跳过
清理旧索引
PG 唯一约束
ES 确定性 ID
```

### 3. ES 和向量分数不能直接相加

BM25 分数和向量相似度不是同一尺度，直接线性加权需要调参。RRF 用排名融合，更稳。

### 4. Redis 删除不能用 KEYS

真实 key 包含 questionHash，不可枚举。线上不建议用 `KEYS` 模糊扫，所以维护 document index set 精确删除。

### 5. ReAct JSON 不能暴露给用户

ReAct 中间 JSON 是执行协议，不是最终答案。前端只展示 finalAnswer，中间步骤写 agent_step。

### 6. prefetch 不能盲目调大

文档任务较重，prefetch 太大会导致消费者囤积多个大文件任务，造成 unacked 堆积和负载不均。项目中设置 prefetch=1，更适合重任务。

### 7. ES 失败不能拖垮 RAG

ES 是增强召回，不应该成为强依赖。因此 BM25 查询异常时返回空列表，RAG 降级为 pgvector-only。

## 十五、压测思路

项目压测不能只看 QPS，要分层压测：

```text
基础页面和登录接口
文档上传接口
RabbitMQ 异步处理吞吐
RAG 冷查询
RAG 热查询
Agent ReAct 多步调用
ES/Redis/Embedding 故障降级
```

核心指标：

```text
QPS
平均响应时间
P95/P99
错误率
RabbitMQ ready/unacked
consumer ack rate
document 状态流转耗时
Redis 命中率
ES 查询耗时
pgvector 查询耗时
Agent 平均执行步数
maxSteps 触发比例
工具调用失败率
```

## 十六、简历推荐写法

项目名称：

```text
基于 ReAct Agentic RAG 的私有知识库智能体系统
```

项目描述：

> 基于 Spring Boot 与 Spring AI 构建私有知识库智能体系统，支持 PDF 文档上传、异步解析、向量化入库、混合检索、流式问答与 ReAct 工具编排。系统结合 PostgreSQL/pgvector、Elasticsearch、Redis 和 RabbitMQ 优化 RAG 检索与文档处理链路，并自研 ReAct Agent 执行器，将 RAG 检索、网页搜索、网页抓取、资源下载、PDF 生成等能力封装为可编排工具，提升复杂任务处理能力和系统扩展性。

推荐亮点：

- 可靠异步文档处理链路
- 消费幂等与数据一致性设计
- pgvector + BM25 + RRF 多路召回
- Redis 热点检索缓存与文档级精准失效
- 自研 ReAct Agent 工具编排
- agent_step 执行链路追踪

## 十七、最终面试收束话术

> 这个项目不是单纯调用大模型做问答，而是围绕私有文档知识库做了一套完整的 Agentic RAG 系统。底层通过 RabbitMQ 解耦文档 ETL，用 PGVector 和 Elasticsearch 做混合检索，用 RRF 做融合排序，用 Redis 做热点检索缓存，并通过状态机、手动 ACK、重试队列、死信队列和幂等索引设计保证文档处理可靠性。在 Agent 层，我基于 Spring AI ChatModel 自研了 ReAct 执行器，把 RAG、网页搜索、网页抓取、资源下载、PDF 生成等能力封装成本地工具，并通过工具白名单、最大步数、observation 注入和 agent_step 落库实现可控、可追踪的复杂任务执行。

