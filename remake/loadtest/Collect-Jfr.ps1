#requires -Version 7.0
# After a run on an app started with Restart-App.ps1 -Jfr: stop the JVM so JFR dumps, copy the
# recording into the run directory, extract execution samples, and summarize the measured window.
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Run)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$out=(Resolve-Path (Join-Path $PSScriptRoot "results/$Run")).Path
$appId='byh-loadtest-app-1'
$jdkImage='eclipse-temurin@sha256:5d6042fb8cdc14d614e4e421f52e3211fc5aeadddce44cbf9de8ed37c791f824'
docker --context desktop-linux stop -t 60 $appId | Out-Null
docker --context desktop-linux cp "${appId}:/tmp/byh.jfr" (Join-Path $out 'app.jfr')
docker --context desktop-linux run --rm -v "${out}:/out" $jdkImage sh -c 'jfr print --json --stack-depth 16 --events jdk.ExecutionSample /out/app.jfr | gzip > /out/jfr-samples.json.gz'
python (Join-Path $PSScriptRoot 'analyze_jfr.py') --run $Run
