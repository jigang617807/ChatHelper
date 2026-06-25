param(
    [ValidateSet('smoke', 'standard', 'full')]
    [string]$Profile = 'standard',
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = '15635201351',
    [string]$Password = '123456.j',
    [string]$Phone = '15635201351',
    [long]$DocId = 0,
    [string]$PdfPath = 'loadtest/test-data/small.pdf',
    [bool]$AutoRegister = $true,
    [ValidateSet('basic', 'enhanced', 'both')]
    [string]$AgentMode = 'basic',
    [switch]$SkipUpload,
    [switch]$SkipRag,
    [switch]$SkipAgent,
    [switch]$KeepUploadedDocs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$k6Root = Join-Path $PSScriptRoot 'k6'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $PSScriptRoot "results/$runId-$Profile"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$k6 = Get-Command k6 -ErrorAction SilentlyContinue
if (-not $k6) {
    throw 'k6 is not installed. Run: winget install k6.k6'
}

$BaseUrl = $BaseUrl.TrimEnd('/')
try {
    $health = Invoke-WebRequest -Uri "$BaseUrl/auth/login" -UseBasicParsing -TimeoutSec 10
    if ($health.StatusCode -ne 200) {
        throw "HTTP $($health.StatusCode)"
    }
} catch {
    throw "The application is not reachable at $BaseUrl. Start Spring Boot first. $($_.Exception.Message)"
}

if ($AutoRegister) {
    try {
        Invoke-WebRequest -Uri "$BaseUrl/auth/register-page" -Method Post -UseBasicParsing -TimeoutSec 15 -Body @{
            username = $Username
            password = $Password
            phone = $Phone
        } | Out-Null
    } catch {
        Write-Warning "Account warm-up registration returned an error. k6 will still verify login. $($_.Exception.Message)"
    }
}

if ((-not $SkipRag -or -not $SkipAgent) -and $DocId -le 0) {
    throw 'DocId must be a COMPLETED document id when RAG or Agent tests are enabled.'
}

$resolvedPdf = $null
if (-not $SkipUpload) {
    $candidatePdf = if ([System.IO.Path]::IsPathRooted($PdfPath)) { $PdfPath } else { Join-Path $repoRoot $PdfPath }
    if (-not (Test-Path -LiteralPath $candidatePdf -PathType Leaf)) {
        throw "PDF not found: $candidatePdf. Run loadtest/scripts/download-pdfs.ps1 or pass -PdfPath."
    }
    $resolvedPdf = (Resolve-Path -LiteralPath $candidatePdf).Path
}

$profiles = @{
    smoke = [ordered]@{
        AuthVus = 2; AuthDuration = '15s'
        UploadVus = 1; UploadIterations = 1
        CacheVus = 1; CacheIterations = 1
        RagLevels = @(1, 2); RagIterations = 2
        AgentVus = 1; AgentIterations = 2
    }
    standard = [ordered]@{
        AuthVus = 5; AuthDuration = '2m'
        UploadVus = 1; UploadIterations = 5
        CacheVus = 1; CacheIterations = 10
        RagLevels = @(1, 2, 4); RagIterations = 20
        AgentVus = 1; AgentIterations = 20
    }
    full = [ordered]@{
        AuthVus = 10; AuthDuration = '5m'
        UploadVus = 2; UploadIterations = 10
        CacheVus = 1; CacheIterations = 30
        RagLevels = @(1, 2, 4, 8); RagIterations = 50
        AgentVus = 2; AgentIterations = 25
    }
}
$settings = $profiles[$Profile]

function Get-SafeCommandText {
    param([string]$Command, [string[]]$Arguments)
    $redacted = $Arguments | ForEach-Object {
        if ($_ -like 'PASSWORD=*') { 'PASSWORD=<redacted>' } else { $_ }
    }
    return "$Command $($redacted -join ' ')"
}

$scenarioResults = [System.Collections.Generic.List[object]]::new()

function Invoke-K6Case {
    param(
        [string]$Name,
        [string]$Script,
        [hashtable]$Environment
    )

    $summaryPath = Join-Path $runDir "$Name.summary.json"
    $logPath = Join-Path $runDir "$Name.log"
    $arguments = @('run', '--summary-export', $summaryPath)
    foreach ($entry in $Environment.GetEnumerator() | Sort-Object Key) {
        $arguments += @('-e', "$($entry.Key)=$($entry.Value)")
    }
    $arguments += $Script

    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    Write-Host (Get-SafeCommandText -Command $k6.Source -Arguments $arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $k6.Source @arguments 2>&1 | Tee-Object -FilePath $logPath
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $scenarioResults.Add([ordered]@{
        name = $Name
        script = $Script
        exitCode = $exitCode
        summary = [System.IO.Path]::GetFileName($summaryPath)
        log = [System.IO.Path]::GetFileName($logPath)
    })
    if ($exitCode -ne 0) {
        Write-Warning "$Name finished with exit code $exitCode. The report will keep the data and mark the failed thresholds."
    }
}

$common = @{
    BASE_URL = $BaseUrl
    USERNAME = $Username
    PASSWORD = $Password
    PHONE = $Phone
    AUTO_REGISTER = 'false'
    RUN_ID = $runId
}

Push-Location $repoRoot
try {
    Invoke-K6Case -Name 'auth-pages' -Script (Join-Path $k6Root 'auth.js') -Environment ($common + @{
        VUS = $settings.AuthVus
        DURATION = $settings.AuthDuration
    })

    if (-not $SkipUpload) {
        Invoke-K6Case -Name 'upload-etl' -Script (Join-Path $k6Root 'upload-etl.js') -Environment ($common + @{
            VUS = $settings.UploadVus
            ITERATIONS = $settings.UploadIterations
            PDF_PATH = $resolvedPdf
            CLEANUP_UPLOADS = (-not $KeepUploadedDocs).ToString().ToLowerInvariant()
        })
    }

    if (-not $SkipRag) {
        Invoke-K6Case -Name 'rag-cache-ab' -Script (Join-Path $k6Root 'rag-cache-ab.js') -Environment ($common + @{
            VUS = $settings.CacheVus
            ITERATIONS = $settings.CacheIterations
            DOC_ID = $DocId
        })

        foreach ($level in $settings.RagLevels) {
            Invoke-K6Case -Name "rag-vu$level" -Script (Join-Path $k6Root 'rag-concurrency.js') -Environment ($common + @{
                VUS = $level
                ITERATIONS = $settings.RagIterations
                DOC_ID = $DocId
            })
        }
    }

    if (-not $SkipAgent) {
        if ($AgentMode -eq 'basic' -or $AgentMode -eq 'both') {
            Invoke-K6Case -Name 'agent-workflow' -Script (Join-Path $k6Root 'agent-workflow.js') -Environment ($common + @{
                VUS = $settings.AgentVus
                ITERATIONS = $settings.AgentIterations
                DOC_ID = $DocId
            })
        }

        if ($AgentMode -eq 'enhanced' -or $AgentMode -eq 'both') {
            Invoke-K6Case -Name 'agent-workflow-enhanced' -Script (Join-Path $k6Root 'agent-workflow-enhanced.js') -Environment ($common + @{
                VUS = $settings.AgentVus
                ITERATIONS = $settings.AgentIterations
                DOC_ID = $DocId
            })
        }
    }
} finally {
    Pop-Location
}

$computer = $null
try { $computer = Get-CimInstance Win32_ComputerSystem } catch { }
$processor = $null
try { $processor = Get-CimInstance Win32_Processor | Select-Object -First 1 } catch { }
$gitCommit = $null
try { $gitCommit = (git -C $repoRoot rev-parse HEAD 2>$null).Trim() } catch { }
$javaVersion = $null
try { $javaVersion = ((java -version 2>&1) -join ' ') } catch { }

$manifest = [ordered]@{
    runId = $runId
    startedAt = (Get-Date).ToString('o')
    profile = $Profile
    agentMode = $AgentMode
    baseUrl = $BaseUrl
    username = $Username
    docId = if ($DocId -gt 0) { $DocId } else { $null }
    pdf = if ($resolvedPdf) { [ordered]@{ path = $resolvedPdf; bytes = (Get-Item $resolvedPdf).Length } } else { $null }
    settings = $settings
    source = [ordered]@{ gitCommit = $gitCommit }
    machine = [ordered]@{
        os = [System.Environment]::OSVersion.VersionString
        processor = if ($processor) { $processor.Name } else { $null }
        logicalProcessors = if ($computer) { $computer.NumberOfLogicalProcessors } else { $null }
        memoryGb = if ($computer) { [math]::Round($computer.TotalPhysicalMemory / 1GB, 2) } else { $null }
        java = $javaVersion
        k6 = (& $k6.Source version | Select-Object -First 1)
    }
    scenarios = $scenarioResults
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $runDir 'manifest.json')

& (Join-Path $PSScriptRoot 'scripts/build-report.ps1') -RunDir $runDir
Write-Host "`nBenchmark finished." -ForegroundColor Green
Write-Host "Report: $(Join-Path $runDir 'benchmark-report.md')"
Write-Host "Metrics: $(Join-Path $runDir 'benchmark-metrics.csv')"
