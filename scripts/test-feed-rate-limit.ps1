param(
  [string]$BaseUrl = "https://daily.teacloud.synology.me",
  [string]$UserToken = "",
  [int]$Requests = 80,
  [int]$Concurrency = 8,
  [string]$Day = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($UserToken)) {
  throw "UserToken ist erforderlich."
}

if ($Requests -lt 1) { $Requests = 1 }
if ($Concurrency -lt 1) { $Concurrency = 1 }
if ($Concurrency -gt $Requests) { $Concurrency = $Requests }

$feedUrl = if ([string]::IsNullOrWhiteSpace($Day)) {
  "$BaseUrl/api/feed"
} else {
  "$BaseUrl/api/feed?day=$Day"
}

$headers = @{ Authorization = "Bearer $UserToken" }
$perWorker = [math]::Ceiling($Requests / $Concurrency)
$jobs = @()

Write-Host "== Feed Rate-Limit Burst =="
Write-Host "URL: $feedUrl"
Write-Host ("Requests={0}, Concurrency={1}, PerWorker~={2}" -f $Requests, $Concurrency, $perWorker)

for ($i = 0; $i -lt $Concurrency; $i++) {
  $jobs += Start-Job -ScriptBlock {
    param($Url, $Hdr, $Count)
    $result = @{
      ok2xx = 0
      rate429 = 0
      err4xx = 0
      err5xx = 0
      other = 0
    }
    for ($n = 0; $n -lt $Count; $n++) {
      try {
        $resp = Invoke-WebRequest -Method GET -Uri $Url -Headers $Hdr -UseBasicParsing
        $code = [int]$resp.StatusCode
      } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status) {
          $code = [int]$status
        } else {
          $code = -1
        }
      }
      if ($code -ge 200 -and $code -lt 300) { $result.ok2xx++ }
      elseif ($code -eq 429) { $result.rate429++ }
      elseif ($code -ge 400 -and $code -lt 500) { $result.err4xx++ }
      elseif ($code -ge 500 -and $code -lt 600) { $result.err5xx++ }
      else { $result.other++ }
    }
    return $result
  } -ArgumentList $feedUrl, $headers, $perWorker
}

Wait-Job $jobs | Out-Null
$all = Receive-Job $jobs
$jobs | Remove-Job -Force | Out-Null

$summary = @{
  ok2xx = 0
  rate429 = 0
  err4xx = 0
  err5xx = 0
  other = 0
}

foreach ($row in $all) {
  $summary.ok2xx += [int]$row.ok2xx
  $summary.rate429 += [int]$row.rate429
  $summary.err4xx += [int]$row.err4xx
  $summary.err5xx += [int]$row.err5xx
  $summary.other += [int]$row.other
}

Write-Host "== Summary =="
$summary.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("{0}: {1}" -f $_.Name, $_.Value) }

if ($summary.err5xx -gt 0) {
  throw ("5xx erkannt ({0}). Soft-Rate-Limit ist nicht stabil genug." -f $summary.err5xx)
}

if ($summary.rate429 -eq 0) {
  Write-Warning "Keine 429 gesehen. Burst evtl. zu klein oder Rate-Limit nicht aktiv."
} else {
  Write-Host "429 erkannt wie erwartet (Soft-Rate-Limit aktiv)."
}
