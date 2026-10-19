#requires -Version 7.0
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
foreach ($repeat in 1,2) {
    & (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image 'byh-loadtest-app:v3-startup' -LoginMaxWaitMs 50 -WarmupIterations 0 -V3Jfr
    & (Join-Path $PSScriptRoot 'Run-Capacity.ps1') -Tps 270 -Repeat $repeat -Label 'v3-diag'
    & (Join-Path $PSScriptRoot 'Collect-V3Jfr.ps1') -Run "v3-diag-270-r$repeat"
}
python (Join-Path $PSScriptRoot 'analyze_capacity.py') --label v3-diag
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python (Join-Path $PSScriptRoot 'update_v3_hashes.py')
exit $LASTEXITCODE
