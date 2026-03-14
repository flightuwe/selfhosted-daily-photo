param(
  [Parameter(Mandatory = $true)]
  [string]$BaseUrl,

  [Parameter(Mandatory = $true)]
  [string]$AdminToken,

  [int]$WindowMinutes = 60
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Url,
    [object]$Body = $null
  )
  $headers = @{
    Authorization = "Bearer $AdminToken"
  }
  if ($null -ne $Body) {
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 8)
  }
  return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers
}

Write-Host "== Trigger Runtime Smoke Check ==" -ForegroundColor Cyan
Write-Host "BaseUrl: $BaseUrl"

$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/health"
if (-not $health.ok) {
  throw "Health check failed"
}
Write-Host "Health: OK (version=$($health.version))" -ForegroundColor Green

$runtime = Invoke-Api -Method Get -Url "$BaseUrl/api/admin/trigger-runtime?windowMinutes=$WindowMinutes"
Write-Host "Runtime status: autoPaused=$($runtime.runtime.autoPaused) tickResult=$($runtime.runtime.lastTickResult)"
Write-Host "Lease: owner=$($runtime.runtime.lease.ownerId) isOwner=$($runtime.runtime.lease.isOwner) expired=$($runtime.runtime.lease.isExpired)"
Write-Host "Recent: attempts=$($runtime.recent.attempts) blocked=$($runtime.recent.blocked) failed=$($runtime.recent.failed) dbLocked=$($runtime.recent.dbLocked)"
if ($runtime.slo) {
  Write-Host "SLO: status=$($runtime.slo.status) violations=$((@($runtime.slo.violations)).Count)"
}

$incidentStatus = Invoke-Api -Method Get -Url "$BaseUrl/api/admin/incidents/export?format=json&statusOnly=true&includeGateway=true"
Write-Host "Incident status: duplicates=$($incidentStatus.status.duplicateAttempts) gatewayLogAvailable=$($incidentStatus.status.gatewayLogAvailable) backendLogAvailable=$($incidentStatus.status.backendLogAvailable)"

if ($incidentStatus.collectionWarnings -and $incidentStatus.collectionWarnings.Count -gt 0) {
  Write-Host "Warnings:" -ForegroundColor Yellow
  $incidentStatus.collectionWarnings | ForEach-Object { Write-Host " - $_" -ForegroundColor Yellow }
}

Write-Host "Smoke check completed." -ForegroundColor Green

