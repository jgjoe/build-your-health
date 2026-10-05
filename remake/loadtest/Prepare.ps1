#requires -Version 7.0
[CmdletBinding()]
param([switch]$ReusePreparedSchema)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out = Join-Path $PSScriptRoot 'results/environment'
New-Item -ItemType Directory -Force $out | Out-Null
function Invoke-SysSql([string]$Sql) {
    $Sql = $Sql.Replace('; ', ";`n")
    $scriptText = "whenever sqlerror exit failure rollback`nset pagesize 0 feedback off linesize 32767`nalter session set container=FREEPDB1;`n$Sql`nexit;"
    $scriptText | docker --context desktop-linux exec -i byh-perf-perfdb-1 sqlplus -s / as sysdba
}
$counts = Invoke-SysSql "select 'SEED|' || (select count(*) from byh_perf.member) || '|' || (select count(*) from byh_perf.orders) || '|' || (select count(*) from byh_perf.order_items) from dual;"
$counts | Set-Content (Join-Path $out 'source-counts.txt')
if (($counts -join '') -notmatch 'SEED\|22021\|600000\|1499546') { throw 'Fixed seed row counts do not match' }
$existing = Invoke-SysSql "select count(*) from dba_users where username='BYH_LOAD';"
if (($existing -join '').Trim() -ne '0' -and -not $ReusePreparedSchema) { throw 'BYH_LOAD already exists; refusing to overwrite it. Use -ReusePreparedSchema after inspection.' }
$password = if ($env:LOAD_DB_PASSWORD) { $env:LOAD_DB_PASSWORD } else { 'byhload123' }
if ($password -notmatch '^[A-Za-z0-9_]+$') { throw 'LOAD_DB_PASSWORD must be alphanumeric for this local harness' }
if (-not $ReusePreparedSchema) {
Invoke-SysSql "create user byh_load identified by $password quota unlimited on users; grant create session, create table, create sequence, create trigger to byh_load; create or replace directory BYH_LOAD_DP as '/opt/oracle/oradata';" | Set-Content (Join-Path $out 'schema-create.txt')
# Local-only default from perf/compose.yaml; can be overridden for a pre-existing volume.
$sysPassword = if ($env:PERF_ORACLE_PASSWORD) { $env:PERF_ORACLE_PASSWORD } else { 'perfsys123' }
docker --context desktop-linux exec byh-perf-perfdb-1 expdp "system/$sysPassword@FREEPDB1" schemas=BYH_PERF directory=BYH_LOAD_DP dumpfile=byh_load_seed.dmp logfile=byh_load_export.log reuse_dumpfiles=yes 2>&1 | Set-Content (Join-Path $out 'export.txt')
docker --context desktop-linux exec byh-perf-perfdb-1 impdp "system/$sysPassword@FREEPDB1" directory=BYH_LOAD_DP dumpfile=byh_load_seed.dmp logfile=byh_load_import.log remap_schema=BYH_PERF:BYH_LOAD exclude=USER 2>&1 | Set-Content (Join-Path $out 'import.txt')
}
$checks = Get-Content -Raw (Join-Path $PSScriptRoot 'checksum.sql') | docker --context desktop-linux exec -i byh-perf-perfdb-1 sqlplus -s / as sysdba
$checks | Set-Content (Join-Path $out 'seed-checksums.txt')
foreach ($table in @('MEMBER','PRODUCT','ORDERS','ITEMS')) {
 $source = @($checks | Where-Object { $_ -like "SOURCE|$table|*" })
 $clone = @($checks | Where-Object { $_ -like "CLONE|$table|*" })
 if ($source.Count -ne 1 -or $clone.Count -ne 1 -or $source[0].Replace('SOURCE|','') -ne $clone[0].Replace('CLONE|','')) { throw "Seed checksum mismatch: $table" }
}
Invoke-SysSql @'
update byh_load.member set password_hash=(select password_hash from byh_load.member where id='demo') where id <> 'demo';
commit;
begin
 for t in (select table_name from dba_tables where owner='BYH_LOAD' and table_name in ('MEMBER','PRODUCT','ORDERS','ORDER_ITEMS')) loop
  dbms_stats.gather_table_stats('BYH_LOAD',t.table_name,cascade=>true,method_opt=>case when t.table_name='ORDERS' then 'FOR ALL COLUMNS SIZE 1 FOR COLUMNS SIZE 254 USER_ID' else 'FOR ALL COLUMNS SIZE 1' end);
 end loop;
end;
/
'@ | Set-Content (Join-Path $out 'fixture-login.txt')
docker --context desktop-linux compose -f (Join-Path $PSScriptRoot 'compose.yaml') up -d --build 2>&1 | Tee-Object -FilePath (Join-Path $out 'build.txt')
docker --context desktop-linux info --format 'Server={{.ServerVersion}} CPU={{.NCPU}} MemoryBytes={{.MemTotal}} Kernel={{.KernelVersion}}' | Set-Content (Join-Path $out 'docker.txt')
docker --context desktop-linux compose version | Add-Content (Join-Path $out 'docker.txt')
Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | Format-List | Out-String | Set-Content (Join-Path $out 'host.txt')
Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory | Format-List | Out-String | Add-Content (Join-Path $out 'host.txt')
docker --context desktop-linux run --rm grafana/k6:latest version | Set-Content (Join-Path $out 'k6.txt')
docker --context desktop-linux exec -e JAVA_TOOL_OPTIONS= byh-loadtest-app-1 java -version 2>&1 | Set-Content (Join-Path $out 'java.txt')
foreach ($image in @('grafana/k6:latest','gvenzl/oracle-free:23-slim-faststart','eclipse-temurin:17-jdk','byh-loadtest-app')) {
 docker --context desktop-linux image inspect $image --format '{{.Id}} {{json .RepoDigests}}' | Add-Content (Join-Path $out 'images.txt')
}
Invoke-SysSql "select banner_full from v`$version; select name || '=' || value from v`$parameter where name in ('optimizer_features_enable','statistics_level','sga_target','pga_aggregate_target'); select 'buffer_cache=' || sum(bytes) from v`$sgastat where name='buffer_cache';" | Set-Content (Join-Path $out 'oracle.txt')
git -C $repo rev-parse HEAD | Set-Content (Join-Path $out 'head.txt')
