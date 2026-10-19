#requires -Version 7.0
# V2 stage 3 before/after comparison (docs/load-test-v2.md section 3.1). Completed runs are skipped on re-run.
# Each session starts a fresh JVM: its first run (mixed 270 TPS) is the cold measurement, the following
# runs on the same JVM are warm. Session order is counterbalanced: B A1 A2 | A2 A1 B.
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$configs=@{
    'before' = @{ Image='byh-loadtest-app:latest'; Max=2; Warmup=20 }
    'a1'     = @{ Image='byh-loadtest-app:stage3'; Max=1; Warmup=20 }
    'a2'     = @{ Image='byh-loadtest-app:stage3'; Max=2; Warmup=20 }
    'a2nowarm' = @{ Image='byh-loadtest-app:stage3'; Max=2; Warmup=0 }
}
function Invoke-Run([string]$Label, [int]$Tps, [int]$Repeat, [string]$Mix='mixed') {
    if (Test-Path (Join-Path $PSScriptRoot "results/$Label-$Tps-r$Repeat/seed-checksums.txt")) { return }
    & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps $Tps -Repeat $Repeat -Label $Label -Mix $Mix
    # Let any overload backlog drain before the next run.
    Start-Sleep -Seconds 20
}
function Start-Session([string]$Name) {
    $c=$configs[$Name]
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image $c.Image -LoginMaxConcurrent $c.Max -WarmupIterations $c.Warmup
}
foreach ($step in @(@('before',1),@('a1',1),@('a2',1),@('a2',2),@('a1',2),@('before',2))) {
    $name,$n=$step
    if (Test-Path (Join-Path $PSScriptRoot "results/s3-$name-warm-400-r$n/seed-checksums.txt")) { continue }
    Start-Session $name
    Invoke-Run "s3-$name-cold" 270 $n
    foreach ($tps in 270,320,400) { Invoke-Run "s3-$name-warm" $tps $n }
    # Per-login BCrypt cost below saturation, once per image (P4).
    if ($n -eq 1 -and $name -in 'before','a2') { Invoke-Run "s3-$name-login" 20 1 'login' }
}
# Cold start without warmup but with the limit, to separate the two changes.
foreach ($n in 1,2) {
    if (Test-Path (Join-Path $PSScriptRoot "results/s3-a2nowarm-cold-270-r$n/seed-checksums.txt")) { continue }
    Start-Session 'a2nowarm'
    Invoke-Run 's3-a2nowarm-cold' 270 $n
}
foreach ($label in 'before','a1','a2' | ForEach-Object { "s3-$_-cold"; "s3-$_-warm" }) {
    python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label $label
}
foreach ($label in 's3-before-login','s3-a2-login','s3-a2nowarm-cold') { python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label $label }
& (Join-Path $PSScriptRoot 'Restart-App.ps1')
Write-Output 'V2_STAGE3_DONE'
