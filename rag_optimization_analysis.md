# RAG 检索与问答优化分析报告

针对本项目（Spring Boot + pgvector + 智谱 AI），我梳理了当前的 RAG 实现现状，并从准确率、召回率及降低幻觉三个维度提出优化方案。

## 1. 当前 RAG 实现现状分析

目前项目的 RAG 流程属于 **"Naive RAG"** 阶段：
- **切片策略**：采用 [TextSplitter](file:///d:/javalearning/demo/src/main/java/com/example/demo/utils/TextSplitter.java#8-83) 递归切分，固定大小和重叠度（800/200）。优点是简单稳定，缺点是可能切断语义。
- **检索方式**：单一的向量检索（pgvector 向量相似度），取 `Top-5`。没有针对关键词的精确匹配。
- **提示词工程**：简单的上下文拼接。没有对模型行为进行严格约束。

---

## 2. 如何提升召回率 (Recall) —— 找得全
召回率决定了系统能否找到包含答案的相关内容。

### 优化方案：
1. **多路召回 (Hybrid Search)**：
   - **现状**：仅使用向量检索。
   - **建议**：在 PostgreSQL 中结合 **Full-Text Search (TSVector)**。向量检索擅长语义理解（如“水果”能搜到“苹果”），全文检索擅长关键词/术语匹配（如搜特定型号“X-100”）。
   - **实现**：使用 PostgreSQL 的 `||` 合并检索结果，或采用 RRF (Reciprocal Rank Fusion) 算法合并排名。

2. **查询重写 (Query Rewriting)**：
   - **建议**：用户的问题往往口语化或不完整。在检索前，先让 LLM 将问题改写成更适合搜索的关键词或多个子问题。

3. **动态分块 (Chunking Optimization)**：
   - **建议**：考虑 **"小分块检索，大分块阅读"**。在数据库存小块（如 200 字）提升检索精度，但在送入 LLM 时，带上该块前后的上下文。

---

## 3. 如何提升准确率 (Accuracy) —— 找得准
准确率决定了在找到的内容中，能否提取出正确答案。

### 优化方案：
1. **重排序 (Re-ranking)**：
   - **原因**：向量相似度高的不一定是答案最相关的。
   - **实现**：先检索出 Top-20 或 Top-50，再调用一个专业的 **Rerank 模型**（如智谱也提供 Rerank 接口）对这 50 个片段进行精细打分，取前 5 个送入 LLM。

2. **多轮对话管理**：
   - **建议**：当前的 `ragPrompt` 包含了全部历史记录，可能导致干扰。建议对历史记录进行总结，提取“当前核心意图”后再进行检索。

---

## 4. 如何减少幻觉 (Reducing Hallucination) —— 不瞎说
防止模型在上下文找不到答案时“一本正经地胡说八道”。

### 优化方案：
1. **严格的 System Prompt 约束**：
   - **建议**：在 Prompt 中明确规定：“你是一个文档问答助手。请**仅根据**提供的相关内容回答。如果内容中没有提到答案，请直接回答‘抱歉，文档中没有相关信息’，**严禁**使用你自己的知识库。”

2. **引用溯源 (Citation)**：
   - **建议**：要求 LLM 在回答时标注来源片段。例如：“根据[片段1]所述，...”。这能强制 LLM 回头检查上下文。

3. **设定相似度阈值**：
   - **建议**：在 [RagService](file:///d:/javalearning/demo/src/main/java/com/example/demo/service/RagService.java#26-84) 中检查 pgvector 返回的相似度分数。如果最高分低于某个阈值（如 0.6），则判定为“未找到相关信息”，直接不调用 LLM。

4. **负反馈输出**：
   - **建议**：提供一个“文档无关”的判断逻辑。先让 LLM 判断问题是否能从提供的 Context 中得到解答。

---

## 5. 针对本项目的具体落地建议 (Next Steps)

1. **Prompt 升级**：这是成本最低且见效最快的方法。修改 [ChatController.java](file:///d:/javalearning/demo/src/main/java/com/example/demo/controller/ChatController.java) 中的 `ragPrompt`。
2. **相似度过滤**：在 [DocumentChunkRepository](file:///d:/javalearning/demo/src/main/java/com/example/demo/repository/DocumentChunkRepository.java#11-25) 的 native query 中返回距离分数（distance），在 Service 层过滤掉分数太高的结果（L2 距离越大越不相关）。
3. **混合检索**：利用 PostgreSQL 已有的全文搜索能力，不需要引入 Elasticsearch 即可实现 Hybrid Search。

**目前我先不改动代码。如果您对上述某个方案感兴趣，我们可以针对性地进行实验和落地。**
