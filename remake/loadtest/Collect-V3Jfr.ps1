#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^v3-[a-z0-9-]+$')][string]$Run)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$out=(Resolve-Path (Join-Path $PSScriptRoot "results/$Run")).Path
$jdkImage='eclipse-temurin@sha256:5d6042fb8cdc14d614e4e421f52e3211fc5aeadddce44cbf9de8ed37c791f824'
docker --context desktop-linux stop -t 60 byh-loadtest-app-1 | Out-Null
docker --context desktop-linux cp 'byh-loadtest-app-1:/tmp/byh.jfr' (Join-Path $out 'app.jfr')
# Separate print and gzip so a failed jfr command cannot be hidden by a successful compressor.
docker --context desktop-linux run --rm -v "${out}:/out" $jdkImage sh -c 'set -e; jfr print --json --stack-depth 16 /out/app.jfr > /out/raw-jfr-events.json; gzip /out/raw-jfr-events.json; mv /out/raw-jfr-events.json.gz /out/jfr-samples.json.gz'
python (Join-Path $PSScriptRoot 'update_v3_hashes.py')
exit $LASTEXITCODE
