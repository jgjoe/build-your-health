#requires -Version 7.0
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
& (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image 'byh-loadtest-app:v3-account' -WarmupIterations 0
python (Join-Path $PSScriptRoot 'account_probe.py') --phase start
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$out=Join-Path $PSScriptRoot 'results/v3-account-policy'
docker --context desktop-linux inspect byh-loadtest-app-1 --format '{{.State.StartedAt}} {{.Image}}' |
    Set-Content (Join-Path $out 'before-restart.txt')
& (Join-Path $PSScriptRoot 'Restart-App.ps1') -Image 'byh-loadtest-app:v3-account' -WarmupIterations 0
docker --context desktop-linux inspect byh-loadtest-app-1 --format '{{.State.StartedAt}} {{.Image}}' |
    Set-Content (Join-Path $out 'after-restart.txt')
docker --context desktop-linux exec byh-loadtest-app-1 sha256sum /app/app.jar |
    Set-Content (Join-Path $out 'jar-sha256.txt')
python (Join-Path $PSScriptRoot 'account_probe.py') --phase finish
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python (Join-Path $PSScriptRoot 'update_v3_hashes.py')
exit $LASTEXITCODE
