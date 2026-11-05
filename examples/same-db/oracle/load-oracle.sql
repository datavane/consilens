-- orders / orders_backup seed (Oracle)
DROP TABLE orders_backup PURGE;
DROP TABLE orders PURGE;
CREATE TABLE orders (
  order_id NUMBER(10) PRIMARY KEY,
  customer_id NUMBER(10),
  amount NUMBER(18,4),
  status VARCHAR2(20),
  created_at DATE
);
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
SELECT n, 100000 + MOD(n,500), ROUND(10 + MOD(n,5000)/10, 4),
  CASE MOD(n,4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
  TO_DATE('2026-05-01', 'YYYY-MM-DD') + MOD(n,10000)/86400
FROM (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 10000);
CREATE TABLE orders_backup AS SELECT * FROM orders;
UPDATE orders_backup SET amount = 99999.9999 WHERE order_id = 1;
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 200001, 8888.0000, 'active', TO_DATE('2026-05-01 12:00:00', 'YYYY-MM-DD HH24:MI:SS'));
COMMIT;
