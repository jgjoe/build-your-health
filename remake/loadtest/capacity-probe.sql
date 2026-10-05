whenever sqlerror exit failure rollback
set pagesize 0 linesize 32767 feedback off heading off trimspool on
alter session set container=FREEPDB1;
select 'UTC|' || to_char(sys_extract_utc(systimestamp),'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"') from dual;
select 'SYSWAIT|' || event || '|' || total_waits || '|' || time_waited_micro from v$system_event where wait_class <> 'Idle' order by event;
select 'DBTIME|' || stat_name || '|' || value from v$sys_time_model where stat_name in ('DB CPU','DB time');
select 'SESSION|' || sid || '|' || serial# || '|' || status || '|' || state || '|' || wait_class || '|' || event || '|' || sql_id from v$session where username='BYH_LOAD' order by sid;
exit;
