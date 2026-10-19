#requires -Version 7.0
# Recreate the measured app container from the existing image with a given Hikari pool size,
# optionally recording JFR (profile settings, dumped on JVM exit), then wait until it serves.
[CmdletBinding()]
param([ValidateRange(1,200)][int]$PoolSize=10, [switch]$Jfr,
    [ValidatePattern('^byh-loadtest-app:[a-z0-9.-]+$')][string]$Image='byh-loadtest-app:latest',
    [ValidateRange(1,64)][int]$LoginMaxConcurrent=2, [ValidateRange(0,1000)][int]$WarmupIterations=20,
    [ValidateRange(0,5000)][int]$LoginMaxWaitMs=50)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$env:POOL_SIZE="$PoolSize"
$env:APP_IMAGE=$Image
$env:LOGIN_MAX_CONCURRENT="$LoginMaxConcurrent"
$env:LOGIN_WARMUP_ITERATIONS="$WarmupIterations"
$env:LOGIN_MAX_WAIT_MS="$LoginMaxWaitMs"
$env:JFR_OPTS=if ($Jfr) { '-XX:StartFlightRecording=filename=/tmp/byh.jfr,settings=profile,dumponexit=true' } else { '' }
docker --context desktop-linux compose -f (Join-Path $PSScriptRoot 'compose.yaml') up -d --no-build --force-recreate app
$deadline=[DateTime]::UtcNow.AddSeconds(180)
while ($true) {
    try {
        if ((Invoke-WebRequest 'http://127.0.0.1:8082/api/products?page=1&size=10' -TimeoutSec 5).StatusCode -eq 200) { break }
    } catch {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'App did not become ready within 180s' }
    }
    Start-Sleep -Seconds 2
}
Write-Output "APP_READY IMAGE=$Image POOL_SIZE=$PoolSize LOGIN_MAX=$LoginMaxConcurrent MAX_WAIT_MS=$LoginMaxWaitMs WARMUP=$WarmupIterations JFR=$($Jfr.IsPresent)"
