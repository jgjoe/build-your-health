#requires -Version 7.0
# Recreate the measured app container from the existing image with a given Hikari pool size,
# optionally recording JFR (profile settings, dumped on JVM exit), then wait until it serves.
[CmdletBinding()]
param([ValidateRange(1,200)][int]$PoolSize=10, [switch]$Jfr)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$env:POOL_SIZE="$PoolSize"
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
Write-Output "APP_READY POOL_SIZE=$PoolSize JFR=$($Jfr.IsPresent)"
