param(
    [Parameter(Mandatory = $true)]
    [string]$RunDir
)

$ErrorActionPreference = 'Stop'
$runPath = (Resolve-Path -LiteralPath $RunDir).Path
$manifestPath = Join-Path $runPath 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "manifest.json not found in $runPath"
}
$manifest = Get-Content -Encoding UTF8 -Raw $manifestPath | ConvertFrom-Json

$definitions = @(
    @{ name = 'home_page_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'document_list_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'page_success'; unit = '%'; kind = 'rate' },
    @{ name = 'upload_accept_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'upload_accept_success'; unit = '%'; kind = 'rate' },
    @{ name = 'etl_complete_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'etl_success'; unit = '%'; kind = 'rate' },
    @{ name = 'etl_timeout'; unit = '%'; kind = 'rate' },
    @{ name = 'etl_completed_documents'; unit = 'count'; kind = 'counter' },
    @{ name = 'rag_cold_total_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'rag_hot_total_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'rag_cold_first_byte_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'rag_hot_first_byte_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'cache_first_byte_speedup_pct'; unit = '%'; kind = 'trend' },
    @{ name = 'cache_pair_success'; unit = '%'; kind = 'rate' },
    @{ name = 'cache_pairs'; unit = 'count'; kind = 'counter' },
    @{ name = 'rag_total_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'rag_first_byte_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'rag_done_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'rag_completed_requests'; unit = 'count'; kind = 'counter' },
    @{ name = 'agent_total_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'agent_first_byte_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'agent_step_count'; unit = 'steps'; kind = 'trend' },
    @{ name = 'agent_tool_call_count'; unit = 'calls'; kind = 'trend' },
    @{ name = 'agent_distinct_tool_count'; unit = 'tools'; kind = 'trend' },
    @{ name = 'agent_tool_latency_ms'; unit = 'ms'; kind = 'trend' },
    @{ name = 'agent_done_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_task_success'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_tool_failure_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_max_step_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_expected_tools_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_min_tool_count_rate'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_single_tool_success'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_two_tool_success'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_multi_tool_success'; unit = '%'; kind = 'rate' },
    @{ name = 'agent_completed_tasks'; unit = 'count'; kind = 'counter' },
    @{ name = 'agent_single_tool_tasks'; unit = 'count'; kind = 'counter' },
    @{ name = 'agent_two_tool_tasks'; unit = 'count'; kind = 'counter' },
    @{ name = 'agent_multi_tool_tasks'; unit = 'count'; kind = 'counter' }
)

function Read-Value {
    param($Metric, [string]$Name)
    if ($null -eq $Metric) { return $null }

    $source = if ($null -ne $Metric.values) { $Metric.values } else { $Metric }
    $property = $source.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Round-Value {
    param($Value, [int]$Digits = 2)
    if ($null -eq $Value) { return $null }
    return [math]::Round([double]$Value, $Digits)
}

$rows = [System.Collections.Generic.List[object]]::new()
Get-ChildItem -LiteralPath $runPath -Filter '*.summary.json' | Sort-Object Name | ForEach-Object {
    $scenario = $_.BaseName -replace '\.summary$', ''
    $summary = Get-Content -Encoding UTF8 -Raw $_.FullName | ConvertFrom-Json
    foreach ($definition in $definitions) {
        $metric = $summary.metrics.PSObject.Properties[$definition.name]
        if ($null -eq $metric) { continue }
        $value = $metric.Value
        $rate = Read-Value $value 'rate'
        $valueRatio = Read-Value $value 'value'
        $rows.Add([pscustomobject][ordered]@{
            scenario = $scenario
            metric = $definition.name
            unit = $definition.unit
            avg = Round-Value (Read-Value $value 'avg')
            median = Round-Value (Read-Value $value 'med')
            p90 = Round-Value (Read-Value $value 'p(90)')
            p95 = Round-Value (Read-Value $value 'p(95)')
            max = Round-Value (Read-Value $value 'max')
            rate_percent = if ($definition.kind -eq 'rate' -and $null -ne $valueRatio) { Round-Value ([double]$valueRatio * 100) } else { $null }
            per_second = if ($definition.kind -eq 'counter' -and $null -ne $rate) { Round-Value $rate 4 } else { $null }
            count = Round-Value (Read-Value $value 'count') 0
        })
    }
}

$csvPath = Join-Path $runPath 'benchmark-metrics.csv'
$rows | Export-Csv -Encoding UTF8 -NoTypeInformation $csvPath

function Find-Row {
    param([string]$Scenario, [string]$Metric)
    return $rows | Where-Object { $_.scenario -eq $Scenario -and $_.metric -eq $Metric } | Select-Object -First 1
}

function Display {
    param($Value, [string]$Suffix = '')
    if ($null -eq $Value -or "$Value" -eq '') { return '-' }
    return "$Value$Suffix"
}

$builder = [System.Text.StringBuilder]::new()
[void]$builder.AppendLine('# Agentic RAG Local Benchmark Report')
[void]$builder.AppendLine()
[void]$builder.AppendLine("- Run ID: ``$($manifest.runId)``")
[void]$builder.AppendLine("- Profile: ``$($manifest.profile)``")
[void]$builder.AppendLine("- Base URL: ``$($manifest.baseUrl)``")
[void]$builder.AppendLine("- Document ID: ``$($manifest.docId)``")
[void]$builder.AppendLine("- Git commit: ``$($manifest.source.gitCommit)``")
[void]$builder.AppendLine("- CPU: $($manifest.machine.processor)")
[void]$builder.AppendLine("- Logical processors / memory: $($manifest.machine.logicalProcessors) / $($manifest.machine.memoryGb) GB")
[void]$builder.AppendLine("- k6: ``$($manifest.machine.k6)``")
[void]$builder.AppendLine()

[void]$builder.AppendLine('## Scenario Status')
[void]$builder.AppendLine()
[void]$builder.AppendLine('| Scenario | Exit code | Summary |')
[void]$builder.AppendLine('| --- | ---: | --- |')
foreach ($scenario in $manifest.scenarios) {
    [void]$builder.AppendLine("| $($scenario.name) | $($scenario.exitCode) | ``$($scenario.summary)`` |")
}
[void]$builder.AppendLine()

[void]$builder.AppendLine('## Core Metrics')
[void]$builder.AppendLine()
[void]$builder.AppendLine('| Scenario | Metric | Average | Median | P95 | Maximum | Rate | Per second | Count |')
[void]$builder.AppendLine('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $rows) {
    [void]$builder.AppendLine("| $($row.scenario) | ``$($row.metric)`` | $(Display $row.avg) | $(Display $row.median) | $(Display $row.p95) | $(Display $row.max) | $(Display $row.rate_percent '%') | $(Display $row.per_second) | $(Display $row.count) |")
}
[void]$builder.AppendLine()

[void]$builder.AppendLine('## Resume Metric Candidates')
[void]$builder.AppendLine()

$upload = Find-Row -Scenario 'upload-etl' -Metric 'upload_accept_ms'
$etl = Find-Row -Scenario 'upload-etl' -Metric 'etl_complete_ms'
$etlRate = Find-Row -Scenario 'upload-etl' -Metric 'etl_success'
$etlCount = Find-Row -Scenario 'upload-etl' -Metric 'etl_completed_documents'
if ($upload -and $etl) {
    [void]$builder.AppendLine("- Async ETL: completed $($etlCount.count) test documents at $($etlCount.per_second) docs/s; upload acceptance P95 $($upload.p95) ms; end-to-end ETL P95 $($etl.p95) ms; completion rate $($etlRate.rate_percent)%.")
}

$cold = Find-Row -Scenario 'rag-cache-ab' -Metric 'rag_cold_first_byte_ms'
$hot = Find-Row -Scenario 'rag-cache-ab' -Metric 'rag_hot_first_byte_ms'
$speedup = Find-Row -Scenario 'rag-cache-ab' -Metric 'cache_first_byte_speedup_pct'
$pairs = Find-Row -Scenario 'rag-cache-ab' -Metric 'cache_pairs'
if ($cold -and $hot) {
    [void]$builder.AppendLine("- Redis hot path: $($pairs.count) paired requests; cold mean first-byte $($cold.avg) ms; hot mean first-byte $($hot.avg) ms; paired mean reduction $($speedup.avg)%.")
}

$ragScenarios = $rows | Where-Object { $_.scenario -like 'rag-vu*' -and $_.metric -eq 'rag_total_ms' } | Sort-Object scenario
foreach ($rag in $ragScenarios) {
    $done = Find-Row -Scenario $rag.scenario -Metric 'rag_done_rate'
    $count = Find-Row -Scenario $rag.scenario -Metric 'rag_completed_requests'
    $concurrency = $rag.scenario -replace 'rag-vu', ''
    [void]$builder.AppendLine("- RAG concurrency ${concurrency}: $($count.count) completed requests at $($count.per_second) requests/s; end-to-end P95 $($rag.p95) ms; completion rate $($done.rate_percent)%.")
}

$agent = Find-Row -Scenario 'agent-workflow' -Metric 'agent_total_ms'
$agentSuccess = Find-Row -Scenario 'agent-workflow' -Metric 'agent_task_success'
$agentSteps = Find-Row -Scenario 'agent-workflow' -Metric 'agent_step_count'
$agentToolFailures = Find-Row -Scenario 'agent-workflow' -Metric 'agent_tool_failure_rate'
if ($agent) {
    [void]$builder.AppendLine("- Agent: structural task success $($agentSuccess.rate_percent)%; mean step count $($agentSteps.avg); end-to-end P95 $($agent.p95) ms; tool-chain failure ratio $($agentToolFailures.rate_percent)%.")
}

$enhancedAgent = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_total_ms'
if ($enhancedAgent) {
    $enhancedSuccess = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_task_success'
    $expectedTools = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_expected_tools_rate'
    $minToolCount = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_min_tool_count_rate'
    $toolCalls = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_tool_call_count'
    $distinctTools = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_distinct_tool_count'
    $singleSuccess = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_single_tool_success'
    $twoSuccess = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_two_tool_success'
    $multiSuccess = Find-Row -Scenario 'agent-workflow-enhanced' -Metric 'agent_multi_tool_success'
    [void]$builder.AppendLine("- Enhanced Agent: structural task success $($enhancedSuccess.rate_percent)%; expected-tool coverage $($expectedTools.rate_percent)%; min tool-count pass $($minToolCount.rate_percent)%; mean tool calls $($toolCalls.avg); mean distinct tools $($distinctTools.avg); single/two/multi success $($singleSuccess.rate_percent)%/$($twoSuccess.rate_percent)%/$($multiSuccess.rate_percent)%; end-to-end P95 $($enhancedAgent.p95) ms.")
}
[void]$builder.AppendLine()

[void]$builder.AppendLine('## Interpretation Limits')
[void]$builder.AppendLine()
[void]$builder.AppendLine('- RAG and Agent metrics include external LLM and Embedding API latency. They are local end-to-end measurements, not pure Java service time.')
[void]$builder.AppendLine('- The cache experiment emphasizes first-byte time. Retrieval runs synchronously before SSE starts, but first-token model variance is still included.')
[void]$builder.AppendLine('- RAG concurrency uses the same user and document, matching the current session model. Conversation history makes repeated runs conservative.')
[void]$builder.AppendLine('- P95 becomes useful at roughly 20 or more valid samples. Prefer median and mean for smaller runs.')
[void]$builder.AppendLine('- A non-zero k6 exit code usually means a threshold failed. Always report success rate with latency.')
[void]$builder.AppendLine('- Agent structural success means FINAL without ERROR. It does not prove semantic answer correctness.')
[void]$builder.AppendLine('- Enhanced Agent expected-tool coverage checks whether the designed tool chain was actually followed; it is stricter than ordinary task success.')

$reportPath = Join-Path $runPath 'benchmark-report.md'
$builder.ToString() | Set-Content -Encoding UTF8 $reportPath
Write-Host "Generated report: $reportPath"
