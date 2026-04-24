# ChatHelper

一个基于 Spring Boot 的本地知识库问答示例项目，支持用户系统、PDF 文档上传、异步文档处理、RAG 检索增强问答，以及基于 SSE 的流式对话体验。

当前项目已经不是单一的 pgvector 检索版本，而是演进为：

- PostgreSQL + pgvector：负责向量召回
- Elasticsearch：负责 BM25 全文召回
- RabbitMQ：负责文档解析与入库异步化
- Zhipu GLM：负责 Embedding 与流式对话生成

## 项目功能

### 1. 用户与会话

- 用户注册、登录、退出登录
- 基于 Session 的登录态管理
- 找回密码流程（当前为短信验证码模拟）
- 个人资料查看与更新
- 用户头像上传

对应入口：

- `/auth/login`
- `/auth/register`
- `/auth/reset`
- `/auth/home`
- `/user/profile`

### 2. 文档上传与管理

- 上传 PDF 文档
- 文档列表展示
- 文档状态跟踪：`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED`
- 删除文档时级联清理相关数据

删除时会一起清理：

- `DocumentChunk`
- Elasticsearch 中的检索文档
- 相关对话与聊天记录
- 本地上传文件

对应入口：

- `GET /doc/upload`
- `POST /doc/upload`
- `GET /doc/list`
- `POST /doc/delete`

### 3. RAG 文档问答

项目实现了一个混合检索版 RAG 流程：

1. 文档上传后进入异步处理队列
2. 后台解析 PDF 文本
3. 文本按 chunk 切分
4. 调用 Embedding 模型生成向量
5. 向量写入 PostgreSQL / pgvector
6. chunk 文本同步写入 Elasticsearch
7. 提问时同时做向量召回和 BM25 召回
8. 对两路结果做融合后生成上下文
9. 调用 GLM 流式输出回答

### 4. 混合检索策略

当前 `RagService` 里已经实现：

- 向量召回
- BM25 召回
- RRF 融合
- 加权融合
- 可切换 rerank 开关（当前为透传占位）

相关配置位于 `application.yml`：

- `rag.retrieval.vector-topk`
- `rag.retrieval.bm25-topk`
- `rag.retrieval.final-topk`
- `rag.retrieval.fusion.strategy`
- `rag.retrieval.fusion.rrf-k`
- `rag.retrieval.fusion.vector-weight`
- `rag.retrieval.fusion.bm25-weight`
- `rag.retrieval.rerank.enabled`

### 5. 流式对话体验

聊天接口使用 SSE：

- `GET /chat/start?docId=...`
- `GET /chat/ask?conversationId=...&documentId=...&question=...`
- `POST /chat/clear`

主要能力：

- 按文档维度恢复历史会话
- 聊天记录持久化
- 流式返回模型输出
- 支持 Markdown 渲染
- 对模型流式片段做规范化处理，避免前端渲染异常

## 核心流程

### 文档处理链路

1. 用户上传 PDF
2. `DocumentController` 保存文件到本地 `uploads/docs/`
3. `DocumentService.saveDocument(...)` 写入文档记录
4. 系统向 RabbitMQ 队列发送 `docId`
5. `DocProcessor` 消费消息并触发异步处理
6. `DocumentService.processDocumentAsync(...)` 完成解析、切块、Embedding、入库与状态更新

### 问答链路

1. 用户进入文档聊天页
2. 提交问题到 `/chat/ask`
3. `ChatController` 校验文档归属
4. `RagService.searchRelevant(...)` 做混合召回
5. 将召回内容拼接为 RAG Prompt
6. 调用 GLM 流式接口
7. 回答通过 SSE 持续返回给前端
8. 最终回答写入聊天记录表

## 主要模块

### controller

- `AuthController`：注册、登录、找回密码、首页
- `UserController`：个人资料、头像上传
- `DocumentController`：文档上传、列表、删除
- `ChatController`：文档会话、SSE 问答、清空聊天记录

### service

- `UserService`：用户相关业务
- `DocumentService`：文档保存、状态管理、切块、入库、删除
- `DocProcessor`：RabbitMQ 消费者
- `RagService`：Embedding、向量召回、BM25 召回、融合检索

### repository

- `DocumentRepository`
- `DocumentChunkRepository`
- `DocumentChunkProjection`
- `DocumentChunkVectorHitProjection`
- `ConversationRepository`
- `ChatMessageRepository`
- `UserRepository`
- `ChunkSearchRepository`

### search

- `ChunkSearchDoc`：Elasticsearch 索引文档，索引名为 `document_chunk`
- `ChunkSearchRepository`：Spring Data Elasticsearch Repository

## 技术栈

- Java 17+
- Spring Boot 3
- Spring MVC
- Spring Data JPA
- Thymeleaf
- PostgreSQL
- pgvector
- Elasticsearch
- RabbitMQ
- PDFBox
- Reactor Flux
- Zhipu OpenAPI SDK

## 运行前准备

### 1. PostgreSQL + pgvector

项目默认数据库配置：

- `DB_URL=jdbc:postgresql://localhost:5432/rag_demo`
- `DB_USERNAME=postgres`
- `DB_PASSWORD=postgres`

建议先创建数据库并启用 `vector` 扩展。

Docker 示例：

```bash
docker run -d --name pgvector-demo \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

进入数据库后执行：

```sql
CREATE DATABASE rag_demo;
\c rag_demo
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. Elasticsearch

项目默认读取：

- `ES_URIS=http://localhost:9200`

这是通过 Spring Boot 自动装配接入的，没有单独手写 ES 配置类。

### 3. RabbitMQ

Docker 示例：

```bash
docker run -d --name rabbitmq-demo \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
```

管理后台：

- [http://localhost:15672](http://localhost:15672)

### 4. 大模型配置

需要环境变量：

- `ZHIPU_API_KEY`

当前 `application.yml` 中的模型配置：

- `zhipu.embedding-model=embedding-3`
- `zhipu.chat-model=glm-4.7-flash`

## 本地启动

```bash
mvn spring-boot:run
```

启动后访问：

- 登录页：[http://localhost:8080/auth/login](http://localhost:8080/auth/login)
- 上传页：[http://localhost:8080/doc/upload](http://localhost:8080/doc/upload)
- 文档列表：[http://localhost:8080/doc/list](http://localhost:8080/doc/list)

## 配置说明

`src/main/resources/application.yml` 里当前关键配置包括：

### 数据源

```yml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/rag_demo}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
```

### Elasticsearch

```yml
spring:
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
```

### 上传目录

```yml
upload:
  dir: .
  root: uploads/
  avatar: uploads/avatar/
  docs: uploads/docs/
```

### 检索参数

```yml
rag:
  retrieval:
    vector-topk: ${RAG_VECTOR_TOPK:20}
    bm25-topk: ${RAG_BM25_TOPK:20}
    final-topk: ${RAG_FINAL_TOPK:5}
```

## 当前项目特点

- 从同步处理演进为 RabbitMQ 异步处理
- 从单路向量检索演进为 pgvector + Elasticsearch 混合召回
- 支持文档级会话隔离
- 支持流式输出和聊天记录持久化
- 支持本地文件与数据库记录联动清理

## 已知现状

- 仓库里存在 `templates副本`、`templates副本2` 这类备份模板目录，当前主程序实际使用的是 `src/main/resources/templates/`
- `rerank` 配置已经预留，但当前 `RagService` 里还是透传占位实现
- README 现在按当前代码更新，但如果后续再改检索链路，建议同步维护

## 适合继续扩展的方向

- 接入真正的短信服务替代模拟验证码
- 给 MQ 消费失败增加重试和死信队列
- 为 Elasticsearch 建立更完整的索引生命周期管理
- 加入管理员视图、审计日志和多用户权限控制
- 为 RAG 检索增加 rerank 模型
- 补充自动化测试和部署说明

## License

本项目主要用于学习、课程设计与功能演示。
