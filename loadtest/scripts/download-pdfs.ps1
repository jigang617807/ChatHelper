param(
    [string]$OutputDir = "loadtest/test-data"
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$files = @(
    @{
        Name = "small.pdf"
        Url = "https://download.pdfsample.com/pdf/sample-1mb.pdf"
        Description = "1MB sample PDF"
    },
    @{
        Name = "medium.pdf"
        Url = "https://download.pdfsample.com/pdf/sample-5mb.pdf"
        Description = "5MB sample PDF"
    },
    @{
        Name = "large.pdf"
        Url = "https://download.pdfsample.com/pdf/sample-25mb.pdf"
        Description = "25MB sample PDF"
    },
    @{
        Name = "max-50mb.pdf"
        Url = "https://download.pdfsample.com/pdf/sample-50mb.pdf"
        Description = "50MB sample PDF, close to this project's upload limit"
    }
)

foreach ($file in $files) {
    $target = Join-Path $OutputDir $file.Name
    Write-Host "Downloading $($file.Description) -> $target"
    Invoke-WebRequest -Uri $file.Url -OutFile $target
    $sizeMb = [Math]::Round((Get-Item $target).Length / 1MB, 2)
    Write-Host "Saved $($file.Name), size=${sizeMb}MB"
}

Write-Host "Done. Test PDFs are in $OutputDir"

