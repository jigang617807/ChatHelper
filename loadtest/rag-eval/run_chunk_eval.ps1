param(
    [string]$Python = "C:\Users\23536\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
    [switch]$StructureOnly,
    [string]$DocsDir = "uploads\docs",
    [string]$Questions = "loadtest\rag-eval\questions.json",
    [string]$OutDir = "loadtest\rag-eval\results"
)

$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"

$argsList = @(
    "loadtest\rag-eval\run_chunk_eval.py",
    "--docs-dir", $DocsDir,
    "--questions", $Questions,
    "--out-dir", $OutDir
)

if ($StructureOnly) {
    $argsList += "--structure-only"
}

& $Python @argsList
