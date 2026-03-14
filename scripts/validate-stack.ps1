param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$AdminToken = "",
    [string]$FromIso = "",
    [string]$ToIso = ""
)

$ErrorActionPreference = "Stop"

function Normalize-BaseUrl {
    param([string]$Url)
    $u = $Url.Trim().TrimEnd('/')
    if (!$u.EndsWith("/api")) { $u = "$u/api" }
    return $u
}

$apiBase = Normalize-BaseUrl -Url $BaseUrl
if ([string]::IsNullOrWhiteSpace($FromIso)) {
    $FromIso = (Get-Date).ToUniversalTime().AddHours(-2).ToString("o")
}
if ([string]::IsNullOrWhiteSpace($ToIso)) {
    $ToIso = (Get-Date).ToUniversalTime().ToString("o")
}

Write-Host "Pruefe Health..."
$health = Invoke-RestMethod -Method GET -Uri "$apiBase/health" -TimeoutSec 20
if (-not $health.ok) {
    throw "Health-Check fehlgeschlagen: ok=false"
}
Write-Host "Health OK. Version: $($health.version)"

if ($AdminToken) {
    $headers = @{ Authorization = "Bearer $AdminToken" }
    Write-Host "Pruefe Trigger Runtime..."
    $runtime = Invoke-RestMethod -Method GET -Uri "$apiBase/admin/trigger-runtime" -Headers $headers -TimeoutSec 20
    Write-Host "Scheduler paused: $($runtime.schedulerPaused)"

    Write-Host "Pruefe Incident Export..."
    $incidentUri = "$apiBase/admin/incidents/export?from=$([uri]::EscapeDataString($FromIso))&to=$([uri]::EscapeDataString($ToIso))&format=json"
    $incident = Invoke-RestMethod -Method GET -Uri $incidentUri -Headers $headers -TimeoutSec 60
    if (-not $incident.meta.schemaVersion) {
        throw "Incident-Export ohne schemaVersion"
    }
    Write-Host "Incident Export OK. Schema: $($incident.meta.schemaVersion)"
}

Write-Host "Stack-Validierung erfolgreich."
