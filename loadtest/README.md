# Local Load Testing

> 新增的一键真实链路基准测试请先阅读 [`BENCHMARK.md`](./BENCHMARK.md)。启动项目后，从仓库根目录运行：
>
> ```powershell
> powershell -ExecutionPolicy Bypass -File .\loadtest\run-benchmark.ps1 -Profile smoke -DocId 你的文档ID
> ```
>
> 每次运行会在 `loadtest/results/` 下生成可追溯的 JSON、CSV 和 Markdown 报告。下文保留原有单脚本调试方法。

这是一组只针对本地项目的压测脚本，不修改任何 Spring Boot 业务代码。

## 1. 准备

先启动你的本地项目，默认按下面地址访问：

```powershell
http://localhost:8080
```

安装 k6 后执行脚本。Windows 可以用：

```powershell
winget install k6.k6
```

下载测试 PDF：

```powershell
powershell -ExecutionPolicy Bypass -File .\loadtest\scripts\download-pdfs.ps1
```

默认下载到：

```text
loadtest/test-data/
```

默认测试用户：

```text
username: loadtest
password: loadtest123
phone: 18800000000
```

如果你的库里还没有这个用户，可以加 `AUTO_REGISTER=true` 让脚本先尝试注册。

## 2. 基础登录压测

```powershell
k6 run .\loadtest\k6\auth.js
```

常用参数：

```powershell
k6 run -e BASE_URL=http://localhost:8080 -e AUTO_REGISTER=true .\loadtest\k6\auth.js
```

## 3. 文档上传压测

小文件：

```powershell
k6 run -e AUTO_REGISTER=true -e PDF_PATH=loadtest/test-data/small.pdf .\loadtest\k6\upload.js
```

中等文件：

```powershell
k6 run -e PDF_PATH=loadtest/test-data/medium.pdf .\loadtest\k6\upload.js
```

大文件：

```powershell
k6 run -e PDF_PATH=loadtest/test-data/large.pdf .\loadtest\k6\upload.js
```

接近项目 50MB 上传限制的文件：

```powershell
k6 run -e PDF_PATH=loadtest/test-data/max-50mb.pdf .\loadtest\k6\upload.js
```

## 4. RAG SSE 问答压测

先在页面上确认一个已经处理完成的文档 ID，然后执行：

```powershell
k6 run -e DOC_ID=1 -e QUESTION="总结一下这个文档" .\loadtest\k6\rag-sse.js
```

脚本会先访问 `/chat/start?docId=...`，从页面里解析 `conversationId`，再请求 `/chat/ask`。

## 5. 首页普通 SSE 问答压测

```powershell
k6 run -e QUESTION="用三句话介绍一下这个系统" .\loadtest\k6\home-sse.js
```

## 6. Agent SSE 压测

普通直接回答：

```powershell
k6 run -e QUESTION="用三句话介绍一下RAG" .\loadtest\k6\agent-sse.js
```

触发工具链的问题会明显更慢，建议低并发开始：

```powershell
k6 run -e VUS=1 -e DURATION=1m -e QUESTION="列出我已经上传的文档" .\loadtest\k6\agent-sse.js
```

## 7. 常用环境变量

```text
BASE_URL          默认 http://localhost:8080
USERNAME          默认 loadtest
PASSWORD          默认 loadtest123
PHONE             默认 18800000000
AUTO_REGISTER     true/false，默认 false
VUS               并发虚拟用户数，默认按脚本内配置
DURATION          持续时间，默认按脚本内配置
PDF_PATH          上传脚本使用的 PDF 文件
DOC_ID            RAG 问答使用的文档 ID
QUESTION          SSE 问答问题
```

## 8. PDF 来源

默认下载脚本使用 PDFSample.com 的直接 PDF 链接：

```text
https://download.pdfsample.com/pdf/sample-1mb.pdf
https://download.pdfsample.com/pdf/sample-5mb.pdf
https://download.pdfsample.com/pdf/sample-25mb.pdf
https://download.pdfsample.com/pdf/sample-50mb.pdf
```

我也查到了 File Examples 的按大小文件矩阵，它明确提供 PDF 的 1MB、5MB、20MB、50MB 等测试文件；如果上面的直链不可用，可以从它的页面手动换源：

```text
https://www.fileexamples.com/sample-sizes
```
