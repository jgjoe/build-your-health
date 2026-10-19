#requires -Version 7.0
# V2 stage 2 cause isolation (docs/load-test-v2.md section 2). Completed runs are skipped on re-run.
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
function Invoke-Run([string]$Label, [int]$Tps, [int]$Repeat) {
    if (Test-Path (Join-Path $PSScriptRoot "results/$Label-$Tps-r$Repeat/seed-checksums.txt")) { return }
    & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps $Tps -Repeat $Repeat -Label $Label
}
# E1, E2: request-type isolation on a freshly started app with the default pool.
& (Join-Path $PSScriptRoot 'Restart-App.ps1') -PoolSize 10
& (Join-Path $PSScriptRoot 'Staircase.ps1') -Label v2-login -Mix login -Stages 20,25,30,35,40
& (Join-Path $PSScriptRoot 'Staircase.ps1') -Label v2-nologin -Mix nologin -Stages 243,480,960
# E3: Hikari pool size, both rates measured for every size (no staircase stop).
foreach ($pool in 5,10,20) {
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -PoolSize $pool
    foreach ($tps in 270,300) { foreach ($repeat in 1,2) { Invoke-Run "v2-pool$pool" $tps $repeat } }
    python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label "v2-pool$pool"
}
# E4: JFR execution samples, one run per rate, each on a fresh JVM so the recording is per run.
foreach ($tps in 270,300) {
    if (Test-Path (Join-Path $PSScriptRoot "results/v2-jfr-$tps-r1/jfr-summary.json")) { continue }
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -PoolSize 10 -Jfr
    Invoke-Run 'v2-jfr' $tps 1
    python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label v2-jfr
    & (Join-Path $PSScriptRoot 'Collect-Jfr.ps1') -Run "v2-jfr-$tps-r1"
}
& (Join-Path $PSScriptRoot 'Restart-App.ps1') -PoolSize 10
Write-Output 'V2_ISOLATION_DONE'
