# RAG Chunk 参数离线评测

这个目录用于评估不同 `chunkSize / overlap` 组合对 RAG 检索效果的影响。它不依赖 Redis、RabbitMQ、PostgreSQL 或 Elasticsearch，只读取 `uploads/docs` 下的 PDF，重新切分并调用与项目一致的智谱 Embedding API 做向量检索实验。

## 一键运行

在项目根目录执行：

```powershell
.\loadtest\rag-eval\run_chunk_eval.ps1
```

如果 Windows PowerShell 禁止运行脚本，可以使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\rag-eval\run_chunk_eval.ps1
```

如果只想验证 PDF 解析和切分统计，不调用 Embedding API：

```powershell
.\loadtest\rag-eval\run_chunk_eval.ps1 -StructureOnly
```

或：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\rag-eval\run_chunk_eval.ps1 -StructureOnly
```

## 环境变量

脚本会自动读取项目根目录 `.env`，也支持直接设置环境变量：

```text
ZHIPU_API_KEY=你的智谱 API Key
ZHIPU_BASE_URL=https://open.bigmodel.cn/api/paas
ZHIPU_EMBEDDING_MODEL=embedding-3
```

默认 Embedding 接口为：

```text
{ZHIPU_BASE_URL}/v4/embeddings
```

## 输出

运行后会在 `loadtest/rag-eval/results` 下生成：

```text
chunk_eval_summary.csv    每组 chunk 参数的汇总指标
chunk_eval_detail.csv     每个问题在每组参数下的检索明细
chunk_eval_report.md      可直接阅读的 Markdown 报告
embedding_cache.json      Embedding 缓存，避免重复消耗 API 额度
```

## 指标解释

- `Recall@K`：TopK 检索结果中是否包含人工标注的证据关键词。
- `MRR`：第一个命中证据的 chunk 排名倒数，越高说明正确证据越靠前。
- `nDCG@K`：考虑相关性强弱和排名位置的排序质量指标。
- `redundancy@K`：TopK chunk 两两文本相似度均值，用来观察 overlap 带来的冗余。
- `avg_topk_est_tokens`：TopK chunk 的估算 token 成本。

## 修改测试问题

编辑 `questions.json` 即可。每条样本包含：

```json
{
  "doc": "PDF 文件名",
  "question": "测试问题",
  "goldKeywords": ["应该出现在证据 chunk 里的关键词"],
  "minKeywordHits": 2,
  "type": "fact"
}
```

脚本会用 `goldKeywords` 判断检索结果是否命中证据。这个方法不是完美标注，但足够用于小规模参数对比。
