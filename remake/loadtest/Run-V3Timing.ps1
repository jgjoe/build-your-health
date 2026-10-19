#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('before','after')][string]$Label)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$image="byh-loadtest-app:v3-$Label-timing"
& (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image $image -LoginMaxConcurrent 2 -LoginMaxWaitMs 50 -WarmupIterations 0
python (Join-Path $PSScriptRoot 'timing_probe.py') --label $Label
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$out=Join-Path $PSScriptRoot "results/v3-timing-$Label"
docker --context desktop-linux inspect byh-loadtest-app-1 --format '{{.State.StartedAt}} {{.Config.Image}} {{.Image}}{{range .Config.Env}}{{println}}{{.}}{{end}}' |
    Where-Object { $_ -notmatch 'PASSWORD' } | Set-Content (Join-Path $out 'app-config.txt')
python (Join-Path $PSScriptRoot 'update_v3_hashes.py')
exit $LASTEXITCODE
