param(
  [string]$BaseUrl = "https://daily.teacloud.synology.me",
  [Parameter(Mandatory = $true)][string]$AdminToken,
  [switch]$Silent,
  [string]$NotifyUserIds = ""
)

$ErrorActionPreference = "Stop"

$ids = @()
if (-not [string]::IsNullOrWhiteSpace($NotifyUserIds)) {
  $ids = $NotifyUserIds.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ }
}

$payload = @{
  silent = [bool]$Silent
  notifyUserIds = $ids
}

$headers = @{
  Authorization = "Bearer $AdminToken"
  "Content-Type" = "application/json"
}

$url = "$BaseUrl/api/admin/prompt/trigger"
Write-Host "Trigger URL: $url"
Write-Host ("Mode: {0}" -f ($(if ($Silent) { "silent" } elseif ($ids.Count -gt 0) { "targeted_users" } else { "broadcast_all" })))
if ($ids.Count -gt 0) {
  Write-Host ("NotifyUserIds: {0}" -f ($ids -join ","))
}

$response = Invoke-RestMethod -Method POST -Uri $url -Headers $headers -Body ($payload | ConvertTo-Json -Depth 5)
$response | ConvertTo-Json -Depth 8
