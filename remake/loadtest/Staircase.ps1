#requires -Version 7.0
[CmdletBinding()]
param([ValidatePattern('^[a-z0-9-]+$')][string]$Label='capacity-before')
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
# Two full observations per stage; a failed first repeat only permits its
# confirmation repeat, never the next higher arrival rate.
foreach ($tps in @(20,40,80,160,320,640,1280,2560,5120,10240,20480)) {
    $stop=$false
    foreach ($repeat in @(1,2)) {
        $out=Join-Path $PSScriptRoot "results/$Label-$tps-r$repeat"
        if (Test-Path $out) {
            if (-not (Test-Path (Join-Path $out 'seed-checksums.txt'))) { throw "Incomplete result: $out" }
        } else {
            & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps $tps -Repeat $repeat -Label $Label
        }
        $summary=Get-Content -Raw (Join-Path $out 'summary.json') | ConvertFrom-Json
        $metrics=$summary.metrics
        $drop=$metrics.PSObject.Properties['dropped_iterations']
        if ($metrics.measured_latency.values.'p(95)' -gt 500 -or $metrics.measured_errors.values.rate -ge 0.01 -or ($drop -and $drop.Value.values.count -gt 0)) { $stop=$true }
        python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label $Label
    }
    Write-Output "STAGE=$tps STOP=$stop"
    if ($stop) { break }
}
