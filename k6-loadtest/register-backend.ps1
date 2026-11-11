param(
    [Parameter(Mandatory = $true)]
    [string]$ManagementUrl,
    [string]$PrometheusUrl = 'http://localhost:9090'
)

$ErrorActionPreference = 'Stop'
$endpoint = [uri]$ManagementUrl
if ($endpoint.Scheme -ne 'http' -or $endpoint.UserInfo -or
    $endpoint.AbsolutePath -notin @('/', '/actuator/prometheus') -or
    $endpoint.Query -or $endpoint.Fragment) {
    throw 'Use http://SERVER:8082 or http://SERVER:8082/actuator/prometheus.'
}

# Docker cannot reach the host through localhost; use its host gateway.
$targetHost = $endpoint.DnsSafeHost
if ($targetHost -in @('localhost', '127.0.0.1')) {
    $targetHost = 'host.docker.internal'
}
if ($targetHost.Contains(':')) { $targetHost = "[$targetHost]" }
$target = '{0}:{1}' -f $targetHost, $endpoint.Port
$destination = Join-Path $PSScriptRoot 'prometheus/targets/sai-backend.json'
$config = @(@{
    targets = @($target)
    labels = @{ application = 'sai-backend'; environment = 'loadtest' }
})
$json = ConvertTo-Json -InputObject $config -Depth 5
[System.IO.File]::WriteAllText($destination, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Registered $target. Waiting for Prometheus file discovery and scrape..."

$required = @(
    'hikaricp_connections_pending',
    'hikaricp_connections_active',
    'hikaricp_connections_idle',
    'hikaricp_connections_max',
    'hikaricp_connections_acquire_seconds_count',
    'hikaricp_connections_acquire_seconds_sum',
    'hikaricp_connections_timeout_total'
)
$deadline = (Get-Date).AddSeconds(55)
do {
    $query = '{job="sai-backend",instance="' + $target + '",__name__=~"hikaricp_.*"}'
    $uri = $PrometheusUrl.TrimEnd('/') + '/api/v1/query?query=' + [uri]::EscapeDataString($query)
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 5
    $names = @($response.data.result | ForEach-Object { $_.metric.__name__ })
    $missing = @($required | Where-Object { $_ -notin $names })
    if ($missing.Count -eq 0) {
        Write-Host 'All required HikariCP metrics are being collected.'
        $response.data.result | ForEach-Object {
            [pscustomobject]@{ Metric = $_.metric.__name__; Pool = $_.metric.pool; Value = $_.value[1] }
        } | Format-Table -AutoSize
        exit 0
    }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

throw "Target saved, but collection is not verified. Missing: $($missing -join ', '). Check $PrometheusUrl/targets, the loadtest profile, and port $($endpoint.Port)."
