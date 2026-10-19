#requires -Version 7.0
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
function Invoke-Run([string]$Label, [int]$Tps, [int]$Repeat) {
    & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps $Tps -Repeat $Repeat -Label $Label
    Start-Sleep -Seconds 20
}
foreach ($step in @(@(50,1),@(75,1),@(75,2),@(50,2))) {
    $wait,$repeat=$step
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image 'byh-loadtest-app:v3-startup' -LoginMaxConcurrent 2 -LoginMaxWaitMs $wait -WarmupIterations 0
    $limits=docker --context desktop-linux inspect byh-loadtest-app-1 --format 'NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}'
    Invoke-Run "v3-wait$wait-cold" 270 $repeat
    $cold=Join-Path $PSScriptRoot "results/v3-wait$wait-cold-270-r$repeat"
    $limits | Set-Content (Join-Path $cold 'resource-limits.txt')
    docker --context desktop-linux exec byh-loadtest-app-1 sha256sum /app/app.jar |
        Set-Content (Join-Path $cold 'jar-sha256.txt')
    foreach ($tps in 270,320,400) {
        Invoke-Run "v3-wait$wait-warm" $tps $repeat
    }
}
foreach ($label in 'v3-wait50-cold','v3-wait50-warm','v3-wait75-cold','v3-wait75-warm') {
    python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label $label
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
python (Join-Path $PSScriptRoot 'evaluate_v3.py')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python (Join-Path $PSScriptRoot 'update_v3_hashes.py')
exit $LASTEXITCODE
