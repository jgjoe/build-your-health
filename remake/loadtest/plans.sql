whenever sqlerror exit failure rollback
set pagesize 0 linesize 32767 feedback off heading off trimspool on
alter session set container=FREEPDB1;
select q.sql_id || '|' || q.child_number || '|' || q.plan_hash_value || '|' || p.plan_table_output
from v$sql q, table(dbms_xplan.display_cursor(q.sql_id,q.child_number,'TYPICAL +PEEKED_BINDS')) p
where q.parsing_schema_name='BYH_LOAD' and q.executions>0
and (upper(ltrim(q.sql_text)) like 'SELECT%' or upper(ltrim(q.sql_text)) like 'INSERT%');
exit;
