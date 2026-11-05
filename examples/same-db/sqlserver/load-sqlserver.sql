-- orders / orders_backup seed (SQL Server)
IF DB_ID('consilens_demo') IS NULL CREATE DATABASE consilens_demo;
GO
USE consilens_demo;
GO
IF OBJECT_ID('orders_backup', 'U') IS NOT NULL DROP TABLE orders_backup;
IF OBJECT_ID('orders', 'U') IS NOT NULL DROP TABLE orders;
GO
CREATE TABLE orders (
  order_id INT PRIMARY KEY,
  customer_id INT,
  amount DECIMAL(18,4),
  status NVARCHAR(20),
  created_at DATETIME
);
GO
;WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
seq AS (SELECT CAST(ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS INT) AS n
  FROM digits ones CROSS JOIN digits tens CROSS JOIN digits hundreds CROSS JOIN digits thousands
  WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000)
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
SELECT n, 100000 + n%500, CAST(ROUND(10 + n%5000/10.0, 4) AS DECIMAL(18,4)),
  CASE n%4 WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
  DATEADD(SECOND, n%10000, '2026-05-01')
FROM seq;
GO
SELECT * INTO orders_backup FROM orders;
GO
UPDATE orders_backup SET amount = 99999.9999 WHERE order_id = 1;
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 200001, 8888.0000, 'active', '2026-05-01 12:00:00');
GO
