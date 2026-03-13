param(
  [string]$BaseUrl = "https://daily.teacloud.synology.me",
  [string]$AdminToken = "",
  [string]$ExpectedServerVersion = ""
)

$ErrorActionPreference = "Stop"

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) {
    throw $Message
  }
}

function Get-Json([string]$Url, [hashtable]$Headers) {
  return Invoke-RestMethod -Method GET -Uri $Url -Headers $Headers
}

Write-Host "== E2E Smoke =="
Write-Host "BaseUrl: $BaseUrl"

$healthUrl = "$BaseUrl/api/health"
$health = Get-Json -Url $healthUrl -Headers @{}
Assert-True ($health.ok -eq $true) "Health check failed: ok != true"
Write-Host ("Health ok, version: {0}" -f $health.version)

if (-not [string]::IsNullOrWhiteSpace($ExpectedServerVersion)) {
  Assert-True ($health.version -eq $ExpectedServerVersion) ("Unexpected server version. expected={0} actual={1}" -f $ExpectedServerVersion, $health.version)
}

if ([string]::IsNullOrWhiteSpace($AdminToken)) {
  Write-Warning "AdminToken fehlt. Admin-Performance-Smoke wird uebersprungen."
  exit 0
}

$headers = @{ Authorization = "Bearer $AdminToken" }

$overviewUrl = "$BaseUrl/api/admin/performance/overview"
$overview = Get-Json -Url $overviewUrl -Headers $headers
Assert-True (-not [string]::IsNullOrWhiteSpace($overview.schemaVersion)) "overview.schemaVersion fehlt"
Assert-True ($null -ne $overview.errorClasses) "overview.errorClasses fehlt"
Assert-True ($null -ne $overview.summary) "overview.summary fehlt"
Assert-True ($null -ne $overview.summary.throttleRate) "overview.summary.throttleRate fehlt"
Assert-True ($null -ne $overview.summary.throttleCount) "overview.summary.throttleCount fehlt"
Write-Host "Overview check ok"

$exportUrl = "$BaseUrl/api/admin/performance/export?format=json"
$export = Get-Json -Url $exportUrl -Headers $headers
Assert-True ($null -ne $export.overview) "export.overview fehlt"
Assert-True ($null -ne $export.routes) "export.routes fehlt"
Assert-True ($null -ne $export.overview.summary) "export.overview.summary fehlt"
Write-Host "Export check ok"

Write-Host "Smoke erfolgreich."
