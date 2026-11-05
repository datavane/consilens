-- orders / orders_backup seed (MySQL 兼容方言)
CREATE DATABASE IF NOT EXISTS mydb_target DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mydb_target;
DROP TABLE IF EXISTS orders_backup;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS consilens_seq;
DROP TABLE IF EXISTS consilens_digits;
CREATE TABLE consilens_digits (d TINYINT NOT NULL PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO consilens_digits (d) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TABLE consilens_seq (n INT NOT NULL PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO consilens_seq (n)
SELECT ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1
FROM consilens_digits ones CROSS JOIN consilens_digits tens
CROSS JOIN consilens_digits hundreds CROSS JOIN consilens_digits thousands
WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000;
CREATE TABLE orders (
  order_id INT NOT NULL PRIMARY KEY,
  customer_id INT,
  amount DECIMAL(18,4),
  status VARCHAR(20),
  created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
SELECT n, 100000 + MOD(n,500), ROUND(10 + MOD(n,5000)/10, 4),
  CASE MOD(n,4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
  DATE_ADD('2026-05-01 00:00:00', INTERVAL MOD(n,10000) SECOND)
FROM consilens_seq;
CREATE TABLE orders_backup LIKE orders;
INSERT INTO orders_backup SELECT * FROM orders;
-- 差异设计（与 cross-db 05 同口径）
UPDATE orders_backup SET amount = 99999.9999 WHERE order_id = 1;
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 200001, 8888.0000, 'active', '2026-05-01 12:00:00');
