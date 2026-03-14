param(
    [Parameter(Mandatory = $true)][string]$BundleZip,
    [Parameter(Mandatory = $true)][string]$TargetBackendDataPath,
    [Parameter(Mandatory = $true)][string]$TargetSecretsPath,
    [string]$TargetStackFilePath = "",
    [string]$TargetEnvFilePath = ""
)

$ErrorActionPreference = "Stop"

if (!(Test-Path $BundleZip)) {
    throw "Bundle nicht gefunden: $BundleZip"
}

$extractRoot = Join-Path $env:TEMP ("daily-restore-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $extractRoot | Out-Null
Expand-Archive -Path $BundleZip -DestinationPath $extractRoot -Force

$manifestPath = Join-Path $extractRoot "manifest.json"
if (!(Test-Path $manifestPath)) {
    throw "manifest.json fehlt im Bundle"
}
$manifest = Get-Content -Path $manifestPath -Raw | ConvertFrom-Json

foreach ($entry in $manifest.files) {
    $filePath = Join-Path $extractRoot ($entry.path -replace '/', '\')
    if (!(Test-Path $filePath)) {
        throw "Datei laut Manifest fehlt: $($entry.path)"
    }
    $hash = (Get-FileHash -Path $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $entry.sha256) {
        throw "Checksum-Fehler bei $($entry.path)"
    }
}

function Copy-Tree {
    param([string]$Source, [string]$Destination)
    if (!(Test-Path $Source)) { return }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Copy-Item -Path (Join-Path $Source "*") -Destination $Destination -Recurse -Force
}

New-Item -ItemType Directory -Path $TargetBackendDataPath -Force | Out-Null
Copy-Tree -Source (Join-Path $extractRoot "backend-data/uploads") -Destination (Join-Path $TargetBackendDataPath "uploads")
if (Test-Path (Join-Path $extractRoot "backend-data/app.db")) {
    Copy-Item -Path (Join-Path $extractRoot "backend-data/app.db") -Destination (Join-Path $TargetBackendDataPath "app.db") -Force
}

Copy-Tree -Source (Join-Path $extractRoot "secrets") -Destination $TargetSecretsPath

if ($TargetStackFilePath -and (Test-Path (Join-Path $extractRoot "config/stack.yml"))) {
    New-Item -ItemType Directory -Path (Split-Path $TargetStackFilePath -Parent) -Force | Out-Null
    Copy-Item -Path (Join-Path $extractRoot "config/stack.yml") -Destination $TargetStackFilePath -Force
}

if ($TargetEnvFilePath -and (Test-Path (Join-Path $extractRoot "config/.env"))) {
    New-Item -ItemType Directory -Path (Split-Path $TargetEnvFilePath -Parent) -Force | Out-Null
    Copy-Item -Path (Join-Path $extractRoot "config/.env") -Destination $TargetEnvFilePath -Force
}

Write-Host "Restore erfolgreich."
Write-Host "Backend data: $TargetBackendDataPath"
Write-Host "Secrets: $TargetSecretsPath"
