import argparse
import csv
import hashlib
import json
import math
import os
import re
import sys
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
from urllib import request, error

from pypdf import PdfReader


DEFAULT_CONFIGS = [
    (500, 100),
    (800, 200),
    (1000, 200),
    (1000, 300),
    (1200, 300),
]


SEPARATORS = [
    "\n\n",
    "\n",
    "。",
    "！",
    "？",
    ".",
    "!",
    "?",
    "；",
    ";",
    "，",
    ",",
    " ",
]


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def normalize_text(text: str) -> str:
    text = text.replace("\ufb01", "fi").replace("\ufb02", "fl")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def compact_for_match(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def extract_pdf_text(pdf_path: Path) -> str:
    reader = PdfReader(str(pdf_path))
    pages = []
    for page in reader.pages:
        pages.append(page.extract_text() or "")
    return normalize_text("\n".join(pages))


def split_text(text: str, max_chunk_size: int, overlap: int) -> List[str]:
    chunks: List[str] = []
    if not text:
        return chunks

    def split_recursive(value: str) -> None:
        if len(value) <= max_chunk_size:
            chunk = value.strip()
            if chunk:
                chunks.append(chunk)
            return

        split_point = -1
        for sep in SEPARATORS:
            idx = value.rfind(sep, 0, max_chunk_size + 1)
            if idx > 0:
                split_point = idx + len(sep)
                break
        if split_point == -1:
            split_point = max_chunk_size

        chunk = value[:split_point].strip()
        if chunk:
            chunks.append(chunk)

        next_start = max(0, split_point - overlap)
        if next_start < len(value):
            remaining = value[next_start:]
            if len(remaining) >= len(value):
                remaining = value[split_point:]
            split_recursive(remaining)

    split_recursive(text)
    return chunks


def estimate_tokens(text: str) -> int:
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    ascii_words = len(re.findall(r"[A-Za-z0-9_]+", text))
    other = max(0, len(text) - cjk)
    return int(math.ceil(cjk * 0.65 + ascii_words * 1.1 + other * 0.08))


def cosine(a: List[float], b: List[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def text_shingles(text: str, n: int = 8) -> set:
    value = compact_for_match(text)
    if len(value) <= n:
        return {value} if value else set()
    return {value[i : i + n] for i in range(0, len(value) - n + 1)}


def jaccard(a: str, b: str) -> float:
    sa = text_shingles(a)
    sb = text_shingles(b)
    if not sa and not sb:
        return 0.0
    return len(sa & sb) / max(1, len(sa | sb))


def redundancy(chunks: List[str]) -> float:
    if len(chunks) < 2:
        return 0.0
    pairs = []
    for i in range(len(chunks)):
        for j in range(i + 1, len(chunks)):
            pairs.append(jaccard(chunks[i], chunks[j]))
    return sum(pairs) / len(pairs)


class EmbeddingClient:
    def __init__(self, cache_path: Path, batch_size: int = 16):
        self.api_key = os.environ.get("ZHIPU_API_KEY", "").strip()
        base_url = os.environ.get("ZHIPU_BASE_URL", "https://open.bigmodel.cn/api/paas").rstrip("/")
        self.endpoint = os.environ.get("ZHIPU_EMBEDDING_ENDPOINT", f"{base_url}/v4/embeddings")
        self.model = os.environ.get("ZHIPU_EMBEDDING_MODEL", os.environ.get("zhipu.embedding-model", "embedding-3"))
        self.batch_size = max(1, batch_size)
        self.cache_path = cache_path.resolve()
        self.cache: Dict[str, List[float]] = {}
        if cache_path.exists():
            self.cache = json.loads(cache_path.read_text(encoding="utf-8"))

    def save(self) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        with self.cache_path.open("w", encoding="utf-8") as fh:
            json.dump(self.cache, fh, ensure_ascii=False)

    def key(self, text: str) -> str:
        payload = f"{self.model}\n{text}".encode("utf-8")
        return hashlib.sha256(payload).hexdigest()

    def embed_many(self, texts: List[str]) -> List[List[float]]:
        if not self.api_key:
            raise RuntimeError("ZHIPU_API_KEY is required for embedding evaluation. Use --structure-only to skip API calls.")

        result: List[Optional[List[float]]] = [None] * len(texts)
        missing: List[Tuple[int, str, str]] = []
        for idx, text in enumerate(texts):
            key = self.key(text)
            cached = self.cache.get(key)
            if cached is not None:
                result[idx] = cached
            else:
                missing.append((idx, key, text))

        for start in range(0, len(missing), self.batch_size):
            batch = missing[start : start + self.batch_size]
            vectors = self._call_api([item[2] for item in batch])
            for (idx, key, _), vector in zip(batch, vectors):
                self.cache[key] = vector
                result[idx] = vector
        if missing:
            self.save()

        return [vec if vec is not None else [] for vec in result]

    def _call_api(self, inputs: List[str]) -> List[List[float]]:
        body = json.dumps({"model": self.model, "input": inputs}, ensure_ascii=False).encode("utf-8")
        req = request.Request(
            self.endpoint,
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with request.urlopen(req, timeout=60) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"Embedding API HTTP {exc.code}: {detail[:500]}") from exc
        except Exception as exc:
            raise RuntimeError(f"Embedding API call failed: {exc}") from exc

        data = payload.get("data")
        if not isinstance(data, list):
            raise RuntimeError(f"Unexpected embedding response: {str(payload)[:500]}")
        data = sorted(data, key=lambda item: item.get("index", 0))
        return [item["embedding"] for item in data]


def keyword_hits(text: str, keywords: List[str]) -> int:
    value = compact_for_match(text)
    hits = 0
    for keyword in keywords:
        if compact_for_match(keyword) in value:
            hits += 1
    return hits


def ndcg_at_k(grades: List[int], k: int) -> float:
    gains = grades[:k]
    dcg = sum(((2**rel - 1) / math.log2(i + 2)) for i, rel in enumerate(gains))
    ideal = sorted(grades, reverse=True)[:k]
    idcg = sum(((2**rel - 1) / math.log2(i + 2)) for i, rel in enumerate(ideal))
    if idcg == 0:
        return 0.0
    return dcg / idcg


def parse_configs(raw: Optional[str]) -> List[Tuple[int, int]]:
    if not raw:
        return DEFAULT_CONFIGS
    configs = []
    for part in raw.split(","):
        size, overlap = part.split("/")
        configs.append((int(size), int(overlap)))
    return configs


def load_questions(path: Path) -> List[dict]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_csv(path: Path, rows: List[dict], fieldnames: List[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def report_markdown(summary_rows: List[dict], detail_rows: List[dict], structure_only: bool) -> str:
    lines = ["# RAG Chunk 参数评测报告", ""]
    if structure_only:
        lines.append("> 本次为 structure-only 模式，只统计切分结构，未调用 Embedding API。")
        lines.append("")
    lines.append("## 汇总")
    lines.append("")
    header = [
        "config",
        "avg_chunks_per_doc",
        "avg_chunk_chars",
        "recall@3",
        "recall@5",
        "mrr",
        "ndcg@5",
        "redundancy@5",
        "avg_top5_est_tokens",
    ]
    lines.append("| " + " | ".join(header) + " |")
    lines.append("| " + " | ".join(["---"] * len(header)) + " |")
    for row in summary_rows:
        lines.append("| " + " | ".join(str(row.get(col, "")) for col in header) + " |")

    if not structure_only and summary_rows:
        sortable = sorted(summary_rows, key=lambda r: (float(r["recall@5"]), float(r["mrr"]), -float(r["redundancy@5"])), reverse=True)
        best = sortable[0]
        lines.extend([
            "",
            "## 初步结论",
            "",
            f"- 当前样本下综合 Recall@5、MRR 和冗余率，表现较好的配置是 `{best['config']}`。",
            "- 如果某组 chunk 更大但 Recall 提升很小，同时 token 成本明显增加，说明它可能不划算。",
            "- 如果 overlap 过大导致 redundancy@5 升高，说明 TopK 里重复上下文变多，会浪费 prompt 空间。",
            "",
            "## 明细文件",
            "",
            "- `chunk_eval_summary.csv`：配置级汇总指标。",
            "- `chunk_eval_detail.csv`：每个问题在每个配置下的命中排名、TopK 命中情况和 token 成本。",
        ])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--docs-dir", default="uploads/docs")
    parser.add_argument("--questions", default="loadtest/rag-eval/questions.json")
    parser.add_argument("--out-dir", default="loadtest/rag-eval/results")
    parser.add_argument("--configs", default=None, help="Comma-separated configs, e.g. 500/100,800/200")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--structure-only", action="store_true")
    args = parser.parse_args()

    project_root = Path.cwd()
    load_dotenv(project_root / ".env")

    docs_dir = Path(args.docs_dir)
    questions_path = Path(args.questions)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    configs = parse_configs(args.configs)
    questions = load_questions(questions_path)
    pdfs = {path.name: path for path in docs_dir.glob("*.pdf")}

    missing_docs = sorted({q["doc"] for q in questions} - set(pdfs))
    if missing_docs:
        raise RuntimeError(f"Questions reference missing PDFs: {missing_docs}")

    print(f"Found {len(pdfs)} PDF files. Loaded {len(questions)} eval questions.")
    pdf_texts = {}
    for name, path in pdfs.items():
        pdf_texts[name] = extract_pdf_text(path)
        print(f"Extracted {name}: {len(pdf_texts[name])} chars")

    detail_rows: List[dict] = []
    summary_rows: List[dict] = []
    embedding_client = None
    if not args.structure_only:
        embedding_client = EmbeddingClient(out_dir / "embedding_cache.json", batch_size=args.batch_size)

    for chunk_size, overlap in configs:
        config_name = f"{chunk_size}/{overlap}"
        print(f"\nRunning config {config_name}")
        doc_chunks: Dict[str, List[str]] = {}
        chunk_counts = []
        chunk_lengths = []
        for doc_name, text in pdf_texts.items():
            chunks = split_text(text, chunk_size, overlap)
            doc_chunks[doc_name] = chunks
            chunk_counts.append(len(chunks))
            chunk_lengths.extend(len(chunk) for chunk in chunks)
            print(f"  {doc_name}: {len(chunks)} chunks")

        if args.structure_only:
            summary_rows.append({
                "config": config_name,
                "avg_chunks_per_doc": round(sum(chunk_counts) / max(1, len(chunk_counts)), 2),
                "avg_chunk_chars": round(sum(chunk_lengths) / max(1, len(chunk_lengths)), 2),
                "recall@3": "",
                "recall@5": "",
                "mrr": "",
                "ndcg@5": "",
                "redundancy@5": "",
                "avg_top5_est_tokens": "",
            })
            continue

        assert embedding_client is not None
        chunk_vectors: Dict[str, List[List[float]]] = {}
        started = time.time()
        for doc_name, chunks in doc_chunks.items():
            chunk_vectors[doc_name] = embedding_client.embed_many(chunks)
        question_vectors = embedding_client.embed_many([q["question"] for q in questions])
        elapsed = time.time() - started
        print(f"  Embedding/cache phase finished in {elapsed:.1f}s")

        recall3_values = []
        recall5_values = []
        mrr_values = []
        ndcg5_values = []
        redundancy_values = []
        token_values = []

        for q_idx, q in enumerate(questions):
            doc_name = q["doc"]
            chunks = doc_chunks[doc_name]
            vectors = chunk_vectors[doc_name]
            qvec = question_vectors[q_idx]
            ranked = sorted(
                [(idx, cosine(qvec, vec)) for idx, vec in enumerate(vectors)],
                key=lambda item: item[1],
                reverse=True,
            )
            keywords = q.get("goldKeywords", [])
            min_hits = int(q.get("minKeywordHits", 1))
            grades_by_chunk = [keyword_hits(chunk, keywords) for chunk in chunks]
            relevant_ranks = [
                rank
                for rank, (idx, _) in enumerate(ranked, start=1)
                if grades_by_chunk[idx] >= min_hits
            ]
            first_rank = relevant_ranks[0] if relevant_ranks else None
            recall3 = 1 if first_rank is not None and first_rank <= 3 else 0
            recall5 = 1 if first_rank is not None and first_rank <= 5 else 0
            mrr = 1 / first_rank if first_rank else 0.0
            ranked_grades = [grades_by_chunk[idx] for idx, _ in ranked]
            ndcg5 = ndcg_at_k(ranked_grades, 5)
            top_chunks = [chunks[idx] for idx, _ in ranked[: args.top_k]]
            red = redundancy(top_chunks)
            top_tokens = sum(estimate_tokens(chunk) for chunk in top_chunks)

            recall3_values.append(recall3)
            recall5_values.append(recall5)
            mrr_values.append(mrr)
            ndcg5_values.append(ndcg5)
            redundancy_values.append(red)
            token_values.append(top_tokens)

            detail_rows.append({
                "config": config_name,
                "doc": doc_name,
                "type": q.get("type", ""),
                "question": q["question"],
                "first_relevant_rank": first_rank if first_rank is not None else "",
                "recall@3": recall3,
                "recall@5": recall5,
                "mrr": round(mrr, 4),
                "ndcg@5": round(ndcg5, 4),
                "redundancy@5": round(red, 4),
                "top5_est_tokens": top_tokens,
                "top1_score": round(ranked[0][1], 6) if ranked else "",
                "top1_preview": top_chunks[0][:160].replace("\n", " ") if top_chunks else "",
            })

        summary_rows.append({
            "config": config_name,
            "avg_chunks_per_doc": round(sum(chunk_counts) / max(1, len(chunk_counts)), 2),
            "avg_chunk_chars": round(sum(chunk_lengths) / max(1, len(chunk_lengths)), 2),
            "recall@3": round(sum(recall3_values) / max(1, len(recall3_values)), 4),
            "recall@5": round(sum(recall5_values) / max(1, len(recall5_values)), 4),
            "mrr": round(sum(mrr_values) / max(1, len(mrr_values)), 4),
            "ndcg@5": round(sum(ndcg5_values) / max(1, len(ndcg5_values)), 4),
            "redundancy@5": round(sum(redundancy_values) / max(1, len(redundancy_values)), 4),
            "avg_top5_est_tokens": round(sum(token_values) / max(1, len(token_values)), 2),
        })

    summary_fields = [
        "config",
        "avg_chunks_per_doc",
        "avg_chunk_chars",
        "recall@3",
        "recall@5",
        "mrr",
        "ndcg@5",
        "redundancy@5",
        "avg_top5_est_tokens",
    ]
    detail_fields = [
        "config",
        "doc",
        "type",
        "question",
        "first_relevant_rank",
        "recall@3",
        "recall@5",
        "mrr",
        "ndcg@5",
        "redundancy@5",
        "top5_est_tokens",
        "top1_score",
        "top1_preview",
    ]
    write_csv(out_dir / "chunk_eval_summary.csv", summary_rows, summary_fields)
    if detail_rows:
        write_csv(out_dir / "chunk_eval_detail.csv", detail_rows, detail_fields)
    (out_dir / "chunk_eval_report.md").write_text(report_markdown(summary_rows, detail_rows, args.structure_only), encoding="utf-8")
    print(f"\nDone. Results written to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
