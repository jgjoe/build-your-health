#requires -Version 7.0
# V2 stage 3b: login permit wait (docs/load-test-v2.md section 3.3). Completed runs are skipped on re-run.
# Same image (byh-loadtest-app:stage3b, no warmup code), only the wait differs. Each session starts a fresh JVM:
# the first run (mixed 270 TPS) is cold, the rest warm. Session order is counterbalanced: W I I W.
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$waits=@{ 'wait50' = 50; 'wait0' = 0 }
function Invoke-Run([string]$Label, [int]$Tps, [int]$Repeat) {
    if (Test-Path (Join-Path $PSScriptRoot "results/$Label-$Tps-r$Repeat/seed-checksums.txt")) { return }
    & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps $Tps -Repeat $Repeat -Label $Label
    Start-Sleep -Seconds 20
}
foreach ($step in @(@('wait50',1),@('wait0',1),@('wait0',2),@('wait50',2))) {
    $name,$n=$step
    if (Test-Path (Join-Path $PSScriptRoot "results/s3b-$name-warm-400-r$n/seed-checksums.txt")) { continue }
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image 'byh-loadtest-app:stage3b' -LoginMaxConcurrent 2 -LoginMaxWaitMs $waits[$name] -WarmupIterations 0
    Invoke-Run "s3b-$name-cold" 270 $n
    foreach ($tps in 270,320,400) { Invoke-Run "s3b-$name-warm" $tps $n }
}
foreach ($label in 'wait50','wait0' | ForEach-Object { "s3b-$_-cold"; "s3b-$_-warm" }) {
    python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label $label
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& (Join-Path $PSScriptRoot 'Restart-App.ps1')
Write-Output 'V2_STAGE3B_DONE'
