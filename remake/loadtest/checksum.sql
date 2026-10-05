whenever sqlerror exit failure rollback
set pagesize 0 linesize 32767 feedback off heading off trimspool on
alter session set container=FREEPDB1;
select 'SOURCE|MEMBER|'||count(*)||'|'||sum(ora_hash(id||'|'||name)) from byh_perf.member;
select 'CLONE|MEMBER|'||count(*)||'|'||sum(ora_hash(id||'|'||name)) from byh_load.member;
select 'SOURCE|PRODUCT|'||count(*)||'|'||sum(ora_hash(product_id||'|'||product_name||'|'||regular_price||'|'||discount_price||'|'||manufacturer)) from byh_perf.product;
select 'CLONE|PRODUCT|'||count(*)||'|'||sum(ora_hash(product_id||'|'||product_name||'|'||regular_price||'|'||discount_price||'|'||manufacturer)) from byh_load.product;
select 'SOURCE|ORDERS|'||count(*)||'|'||sum(ora_hash(user_id||'|'||to_char(order_date,'YYYYMMDDHH24MISS')||'|'||to_char(delivery_date,'YYYYMMDD')||'|'||total_price||'|'||recipient_name||'|'||shipping_address||'|'||shipping_zipcode)) from byh_perf.orders;
select 'CLONE|ORDERS|'||count(*)||'|'||sum(ora_hash(user_id||'|'||to_char(order_date,'YYYYMMDDHH24MISS')||'|'||to_char(delivery_date,'YYYYMMDD')||'|'||total_price||'|'||recipient_name||'|'||shipping_address||'|'||shipping_zipcode)) from byh_load.orders;
select 'SOURCE|ITEMS|'||count(*)||'|'||sum(ora_hash(o.user_id||'|'||to_char(o.order_date,'YYYYMMDDHH24MISS')||'|'||oi.product_id||'|'||oi.quantity||'|'||oi.price)) from byh_perf.order_items oi join byh_perf.orders o on o.order_id=oi.order_id;
select 'CLONE|ITEMS|'||count(*)||'|'||sum(ora_hash(o.user_id||'|'||to_char(o.order_date,'YYYYMMDDHH24MISS')||'|'||oi.product_id||'|'||oi.quantity||'|'||oi.price)) from byh_load.order_items oi join byh_load.orders o on o.order_id=oi.order_id;
exit;
