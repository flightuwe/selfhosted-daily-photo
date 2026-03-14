param(
    [Parameter(Mandatory = $true)][string]$BackendDataPath,
    [Parameter(Mandatory = $true)][string]$SecretsPath,
    [string]$StackFilePath = "",
    [string]$EnvFilePath = "",
    [string]$OutputDir = ".\\migration-backups",
    [string]$Label = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Label)) {
    $Label = Get-Date -Format "yyyyMMdd-HHmmss"
}

$bundleName = "daily-migration-$Label"
$stagingRoot = Join-Path $env:TEMP $bundleName

if (Test-Path $stagingRoot) {
    Remove-Item -Recurse -Force $stagingRoot
}
New-Item -ItemType Directory -Path $stagingRoot | Out-Null
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$bundleZip = Join-Path (Resolve-Path $OutputDir) "$bundleName.zip"

function Copy-IfExists {
    param([string]$Source, [string]$Destination)
    if (Test-Path $Source) {
        New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
        if ((Get-Item $Source).PSIsContainer) {
            Copy-Item -Path $Source -Destination $Destination -Recurse -Force
        } else {
            Copy-Item -Path $Source -Destination $Destination -Force
        }
    }
}

Copy-IfExists -Source (Join-Path $BackendDataPath "app.db") -Destination (Join-Path $stagingRoot "backend-data/app.db")
Copy-IfExists -Source (Join-Path $BackendDataPath "uploads") -Destination (Join-Path $stagingRoot "backend-data/uploads")
Copy-IfExists -Source $SecretsPath -Destination (Join-Path $stagingRoot "secrets")
if ($StackFilePath) { Copy-IfExists -Source $StackFilePath -Destination (Join-Path $stagingRoot "config/stack.yml") }
if ($EnvFilePath) { Copy-IfExists -Source $EnvFilePath -Destination (Join-Path $stagingRoot "config/.env") }

$files = Get-ChildItem -Path $stagingRoot -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($stagingRoot.Length).TrimStart('\')
    [PSCustomObject]@{
        path = $relative -replace '\\', '/'
        size = $_.Length
        sha256 = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$manifest = [PSCustomObject]@{
    schemaVersion = "daily_migration_bundle_v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    label = $Label
    fileCount = $files.Count
    files = $files
}
$manifestPath = Join-Path $stagingRoot "manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding UTF8

if (Test-Path $bundleZip) {
    Remove-Item -Force $bundleZip
}
Compress-Archive -Path (Join-Path $stagingRoot "*") -DestinationPath $bundleZip -CompressionLevel Optimal

$bundleHash = (Get-FileHash -Path $bundleZip -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Backup erstellt: $bundleZip"
Write-Host "SHA256: $bundleHash"
