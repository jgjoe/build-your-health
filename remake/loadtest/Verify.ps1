#requires -Version 7.0
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$remake=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$results=Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force $results | Out-Null
docker --context desktop-linux build --target build -t byh-loadtest-build $remake 2>&1 | Set-Content (Join-Path $results 'verify-build.txt')
docker --context desktop-linux create --name byh-loadtest-verify-copy --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /build byh-loadtest-build sh mvnw -B verify | Out-Null
try {
 docker --context desktop-linux start -a byh-loadtest-verify-copy 2>&1 | Tee-Object -FilePath (Join-Path $results 'verify-copy.txt')
 $code=docker --context desktop-linux inspect byh-loadtest-verify-copy --format '{{.State.ExitCode}}'
 $code | Set-Content (Join-Path $results 'verify-exit-code.txt')
 docker --context desktop-linux cp byh-loadtest-verify-copy:/build/target/surefire-reports (Join-Path $results 'test-reports')
 if ([int]$code -ne 0) { throw "Maven verify failed: $code" }
} finally {
 docker --context desktop-linux rm -f byh-loadtest-verify-copy | Out-Null
}
