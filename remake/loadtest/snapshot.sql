whenever sqlerror exit failure rollback
set pagesize 0 linesize 32767 feedback off heading off trimspool on
alter session set container=FREEPDB1;
select 'UTC|' || to_char(sys_extract_utc(systimestamp),'YYYY-MM-DD"T"HH24:MI:SS.FF3') from dual;
select 'ROWS|' || (select count(*) from byh_load.member) || '|' || (select count(*) from byh_load.product) || '|' || (select count(*) from byh_load.orders) || '|' || (select count(*) from byh_load.order_items) from dual;
select 'WAIT|' || e.event || '|' || sum(e.total_waits) || '|' || sum(e.time_waited_micro)
from v$session_event e join v$session s on s.sid=e.sid
where s.username='BYH_LOAD' and e.wait_class <> 'Idle' group by e.event order by e.event;
select 'SQL|' || sql_id || '|' || child_number || '|' || plan_hash_value || '|' || executions || '|' || buffer_gets || '|' || elapsed_time || '|' || cpu_time || '|' || user_io_wait_time || '|' || concurrency_wait_time || '|' || application_wait_time || '|' || rows_processed || '|' || regexp_replace(substr(sql_text,1,1000),'[[:space:]]+',' ')
from v$sql where parsing_schema_name='BYH_LOAD' order by sql_id,child_number;
select 'POOLSESSION|' || status || '|' || count(*) from v$session where username='BYH_LOAD' group by status;
exit;
