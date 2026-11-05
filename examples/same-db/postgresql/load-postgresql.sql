-- orders / orders_backup seed (PostgreSQL)
SELECT 'CREATE DATABASE consilens_demo' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'consilens_demo')\gexec
\connect consilens_demo
SET TIME ZONE 'Asia/Shanghai';
DROP TABLE IF EXISTS public.orders_backup;
DROP TABLE IF EXISTS public.orders;
CREATE TABLE public.orders (
  order_id INT PRIMARY KEY,
  customer_id INT,
  amount NUMERIC(18,4),
  status VARCHAR(20),
  created_at TIMESTAMP
);
INSERT INTO public.orders (order_id, customer_id, amount, status, created_at)
SELECT n, 100000 + MOD(n,500), ROUND(10 + MOD(n,5000)/10.0, 4),
  CASE MOD(n,4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
  TIMESTAMP '2026-05-01 00:00:00' + (MOD(n,10000) || ' seconds')::interval
FROM generate_series(1, 10000) AS n;
CREATE TABLE public.orders_backup AS SELECT * FROM public.orders;
UPDATE public.orders_backup SET amount = 99999.9999 WHERE order_id = 1;
INSERT INTO public.orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 200001, 8888.0000, 'active', '2026-05-01 12:00:00');
