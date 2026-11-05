-- SQL Server seed data for mysql-sqlserver comparison test
-- Purpose: MySQL source has "correct" data; SQL Server target has known differences
-- Connect to SQL Server via port 1433

USE master;
GO

-- Create databases
IF DB_ID('consilens_demo') IS NULL CREATE DATABASE consilens_demo;
IF DB_ID('mydb') IS NULL CREATE DATABASE mydb;
IF DB_ID('production') IS NULL CREATE DATABASE production;
GO

USE consilens_demo;
GO

-- =========================================================
-- sequence helper: recursive CTE for number generation
-- =========================================================

-- =========================================================
-- consilens_performance_demo_table (SQL Server target)
-- =========================================================

IF OBJECT_ID('consilens_performance_demo_table', 'U') IS NOT NULL
    DROP TABLE consilens_performance_demo_table;
GO

CREATE TABLE consilens_performance_demo_table (
    record_id NVARCHAR(16) NOT NULL, col_tinyint TINYINT, col_smallint SMALLINT,
    col_mediumint INT, col_int INT, col_bigint BIGINT,
    col_unsigned_int BIGINT, col_float REAL, col_double FLOAT,
    col_decimal DECIMAL(18,4), col_numeric NUMERIC(18,4), col_char CHAR(10),
    col_varchar_50 NVARCHAR(50), col_varchar_100 NVARCHAR(100), col_varchar_255 NVARCHAR(255),
    col_text NVARCHAR(MAX), col_mediumtext NVARCHAR(MAX), col_binary BINARY(8),
    col_varbinary VARBINARY(16), col_blob VARBINARY(MAX), col_date DATE,
    col_datetime DATETIME, col_timestamp DATETIME, col_time TIME,
    col_boolean BIT, col_tinyint_bool TINYINT,
    col_enum NVARCHAR(20), col_set NVARCHAR(20),
    col_json NVARCHAR(MAX), user_name NVARCHAR(64), email NVARCHAR(128), phone NVARCHAR(32),
    address NVARCHAR(255), city NVARCHAR(64), country NVARCHAR(64), postal_code NVARCHAR(20),
    amount DECIMAL(18,4), balance DECIMAL(18,4), credit_limit DECIMAL(18,4),
    status NVARCHAR(20), category NVARCHAR(30), priority SMALLINT, score FLOAT,
    created_at DATETIME, updated_at DATETIME, deleted TINYINT NOT NULL DEFAULT 0,
    dt DATE NOT NULL,
    CONSTRAINT pk_perf_demo PRIMARY KEY (record_id)
);
GO

-- Insert with TARGET_MISSING: skip n=5
-- NOTE: n is cast to BIGINT in the CTE to avoid arithmetic overflow
;WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT CAST(ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS BIGINT) AS n
    FROM digits ones CROSS JOIN digits tens CROSS JOIN digits hundreds CROSS JOIN digits thousands
    WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000
)
INSERT INTO consilens_performance_demo_table
SELECT
    CONCAT('REC', RIGHT('0000000000' + CAST(n AS NVARCHAR), 10)), n % 100, n % 30000, n*3, n*10, n*1000003,
    2147483648 + n, CAST(n%1000 + 0.125 AS REAL), CAST(n%100000 + 0.25 AS FLOAT),
    CAST(ROUND(n%100000/100.0 + 0.1234, 4) AS DECIMAL(18,4)),
    CAST(ROUND(n%100000/50.0 + 0.5678, 4) AS DECIMAL(18,4)),
    CONCAT('C', RIGHT('000000000' + CAST(n AS NVARCHAR), 9)), CONCAT('v50_', RIGHT('00000' + CAST(n AS NVARCHAR), 5)),
    CONCAT('v100_', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '_stable'), CONCAT('v255_', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '_stable_payload'),
    CONCAT('text-', RIGHT('00000' + CAST(n AS NVARCHAR), 5)), CONCAT('mediumtext-', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '-', REPLICATE('x', CAST(n%32 AS INT))),
    CONVERT(BINARY(8), CAST(n AS BINARY(8))), CONVERT(VARBINARY(16), CAST(n*17 AS BINARY(16))), CONVERT(VARBINARY(MAX), CAST(n*31 AS BINARY(16))),
    DATEADD(DAY, CAST(n%7 AS INT), '2026-05-01'),
    DATEADD(SECOND, CAST(n%10000 AS INT), '2026-05-01'),
    DATEADD(SECOND, CAST(n%10000 AS INT), '2026-05-01'),
    CONVERT(TIME, DATEADD(SECOND, CAST(n%86400 AS INT), '00:00:00')), CASE WHEN n%2=0 THEN 1 ELSE 0 END, CAST(n%2 AS TINYINT),
    CASE n%4 WHEN 0 THEN 'new' WHEN 1 THEN 'processing' WHEN 2 THEN 'done' ELSE 'failed' END,
    CASE n%3 WHEN 0 THEN 'a,b' WHEN 1 THEN 'b' ELSE 'c' END,
    CONCAT('{"value": "json_', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '"}'),
    CONCAT('user_', RIGHT('00000' + CAST(n AS NVARCHAR), 5)), CONCAT('user_', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '@example.com'),
    CONCAT('+861380', RIGHT('000000' + CAST(n AS NVARCHAR), 6)), CONCAT('No.', CAST(n AS NVARCHAR), ' Consilens Road'),
    CASE n%4 WHEN 0 THEN 'Shanghai' WHEN 1 THEN 'Beijing' WHEN 2 THEN 'Shenzhen' ELSE 'Hangzhou' END,
    'CN', RIGHT('000000' + CAST(n%1000000 AS NVARCHAR), 6),
    CAST(ROUND(10 + n%5000/10.0, 4) AS DECIMAL(18,4)),
    CAST(ROUND(1000 + n%8000/10.0, 4) AS DECIMAL(18,4)),
    CAST(ROUND(5000 + n%3000/10.0, 4) AS DECIMAL(18,4)),
    CASE n%4 WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
    CASE n%5 WHEN 0 THEN 'retail' WHEN 1 THEN 'finance' WHEN 2 THEN 'logistics' WHEN 3 THEN 'manufacturing' ELSE 'public' END,
    CAST(n%5+1 AS SMALLINT), CAST(n%100 + 0.5 AS FLOAT),
    DATEADD(SECOND, CAST(n%10000 AS INT), '2026-05-01'),
    DATEADD(SECOND, CAST(n%10000 AS INT), '2026-05-01 00:05:00'),
    CASE WHEN n%10=0 THEN 1 ELSE 0 END, '2026-05-01'
FROM seq
WHERE n != 5;  -- TARGET_MISSING: row REC0000000005 intentionally absent
GO

-- MISMATCH: modify specific records
UPDATE consilens_performance_demo_table SET amount = 99999.9999 WHERE record_id = 'REC0000000001';
UPDATE consilens_performance_demo_table SET status = 'modified_status' WHERE record_id = 'REC0000000002';
GO

-- SOURCE_MISSING: extra record in target
INSERT INTO consilens_performance_demo_table
(record_id, col_tinyint, col_int, col_decimal, amount, status, updated_at, dt)
VALUES ('REC_EXTRA_001', 1, 100, 100.1234, 5000.0000, 'extra', GETDATE(), '2026-05-01');
GO

-- =========================================================
-- users table
-- =========================================================

USE mydb;
GO

IF OBJECT_ID('users', 'U') IS NOT NULL DROP TABLE users;
GO

CREATE TABLE users (
    id INT NOT NULL PRIMARY KEY, name NVARCHAR(100), email NVARCHAR(128) NOT NULL,
    phone NVARCHAR(32), status NVARCHAR(20), created_at DATETIME
);
GO

;WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS n
    FROM digits ones CROSS JOIN digits tens CROSS JOIN digits hundreds CROSS JOIN digits thousands
    WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000
)
INSERT INTO users
SELECT n, CONCAT('user_', RIGHT('00000' + CAST(n AS NVARCHAR), 5)), CONCAT('user_', RIGHT('00000' + CAST(n AS NVARCHAR), 5), '@example.com'),
    CONCAT('+861380', RIGHT('000000' + CAST(n AS NVARCHAR), 6)),
    CASE n%3 WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' ELSE 'pending' END,
    DATEADD(SECOND, n%10000, '2026-01-01')
FROM seq;
GO

-- MISMATCH: 修改 id=1 的 email
UPDATE users SET email = 'modified@example.com' WHERE id = 1;
GO

-- =========================================================
-- orders table
-- =========================================================

IF OBJECT_ID('orders_backup', 'U') IS NOT NULL DROP TABLE orders_backup;
IF OBJECT_ID('orders', 'U') IS NOT NULL DROP TABLE orders;
GO

CREATE TABLE orders (
    order_id BIGINT NOT NULL PRIMARY KEY, customer_id INT NOT NULL,
    amount DECIMAL(18,4) NOT NULL, status NVARCHAR(20) NOT NULL, created_at DATETIME NOT NULL
);
GO

;WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS n
    FROM digits ones CROSS JOIN digits tens CROSS JOIN digits hundreds CROSS JOIN digits thousands
    WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000
)
INSERT INTO orders
SELECT n, 100000 + n%500, CAST(ROUND(20 + n%10000/20.0, 4) AS DECIMAL(18,4)),
    CASE n%4 WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END,
    DATEADD(DAY, n%365, '2025-01-01')
FROM seq;
GO

SELECT * INTO orders_backup FROM orders;
GO

-- MISMATCH: orders_backup 修改某订单 amount
UPDATE orders_backup SET amount = 99999.9999 WHERE order_id = 1;
GO

-- TARGET_MISSING: source 端 orders 插入额外订单（target 端 orders_backup 缺失）
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 100500, 999.99, 'paid', '2025-06-15 12:00:00');
GO

-- =========================================================
-- fact_orders table
-- =========================================================

USE production;
GO

IF OBJECT_ID('fact_orders', 'U') IS NOT NULL DROP TABLE fact_orders;
GO

CREATE TABLE fact_orders (
    order_id BIGINT NOT NULL PRIMARY KEY, customer_id INT NOT NULL, product_id INT NOT NULL,
    quantity INT NOT NULL, unit_price DECIMAL(18,4) NOT NULL, total_amount DECIMAL(18,4) NOT NULL,
    order_date DATE NOT NULL, status NVARCHAR(20) NOT NULL, created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
GO

;WITH digits AS (
    SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
seq AS (
    SELECT ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS n
    FROM digits ones CROSS JOIN digits tens CROSS JOIN digits hundreds CROSS JOIN digits thousands
    WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000
)
INSERT INTO fact_orders
SELECT order_id, customer_id, product_id, quantity, unit_price,
    CAST(quantity*unit_price AS DECIMAL(18,4)), order_date, status, created_at, updated_at
FROM (
    SELECT n AS order_id, 100000 + n%500 AS customer_id, 200000 + n%1000 AS product_id,
        1 + n%10 AS quantity, CAST(ROUND(5 + n%2000/10.0, 4) AS DECIMAL(18,4)) AS unit_price,
        DATEADD(DAY, n%30, '2026-05-01') AS order_date,
        CASE n%4 WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END AS status,
        DATEADD(SECOND, n%10000, '2026-05-01') AS created_at,
        DATEADD(SECOND, n%10000, '2026-05-01 00:10:00') AS updated_at
    FROM seq
) s;
GO

-- =========================================================
-- daily_order_summary (聚合表，用于 detail-to-aggregate 测试)
-- =========================================================

USE consilens_demo;
GO

IF OBJECT_ID('daily_order_summary', 'U') IS NOT NULL DROP TABLE daily_order_summary;
GO

CREATE TABLE daily_order_summary (
    biz_date DATE NOT NULL,
    status NVARCHAR(20) NOT NULL,
    order_count BIGINT NOT NULL,
    total_amount DECIMAL(38,4) NOT NULL,
    updated_at DATETIME,
    CONSTRAINT pk_daily_order_summary PRIMARY KEY (biz_date, status)
);
GO

INSERT INTO daily_order_summary
SELECT
    CAST(created_at AS DATE) AS biz_date,
    status,
    COUNT(*) AS order_count,
    CAST(SUM(amount) AS DECIMAL(38,4)) AS total_amount,
    MAX(updated_at) AS updated_at
FROM consilens_performance_demo_table
WHERE deleted = 0
GROUP BY CAST(created_at AS DATE), status;
GO

-- =========================================================
-- validation
-- =========================================================

USE consilens_demo;
GO

SELECT 'sqlserver.consilens_performance_demo_table' AS check_name,
    COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(record_id) AS min_record_id, MAX(record_id) AS max_record_id,
    ROUND(SUM(amount), 4) AS amount_sum
FROM consilens_performance_demo_table;

SELECT 'sqlserver.consilens_demo.daily_order_summary' AS check_name,
    COUNT(*) AS actual_groups,
    ROUND(SUM(total_amount), 4) AS total_amount_sum
FROM daily_order_summary;

USE mydb;
GO

SELECT 'sqlserver.users' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(id) AS min_id, MAX(id) AS max_id FROM users;

SELECT 'sqlserver.orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(amount), 4) AS amount_sum FROM orders;

USE production;
GO

SELECT 'sqlserver.fact_orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(total_amount), 4) AS total_amount_sum FROM fact_orders;
GO
