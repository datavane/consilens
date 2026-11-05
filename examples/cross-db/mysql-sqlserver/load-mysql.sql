-- MySQL seed data for mysql-sqlserver comparison test
-- Target row count: 10000 rows in each source table
-- This is the same base data as mysql-pg; SQL Server target has intentional inconsistencies

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS consilens_demo
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS mydb
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS production
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS diff_results
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE consilens_demo;

-- =========================================================
-- sequence helper tables
-- =========================================================

DROP TABLE IF EXISTS consilens_digits;
CREATE TABLE consilens_digits (d TINYINT NOT NULL PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO consilens_digits (d) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS consilens_seq;
CREATE TABLE consilens_seq (n INT NOT NULL PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO consilens_seq (n)
SELECT ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 + 1 AS n
FROM consilens_digits ones CROSS JOIN consilens_digits tens
CROSS JOIN consilens_digits hundreds CROSS JOIN consilens_digits thousands
WHERE ones.d + tens.d*10 + hundreds.d*100 + thousands.d*1000 < 10000
ORDER BY n;

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.consilens_performance_demo_table;

CREATE TABLE consilens_demo.consilens_performance_demo_table (
    record_id VARCHAR(16) NOT NULL, col_tinyint TINYINT, col_smallint SMALLINT,
    col_mediumint MEDIUMINT, col_int INT, col_bigint BIGINT,
    col_unsigned_int INT UNSIGNED, col_float FLOAT, col_double DOUBLE,
    col_decimal DECIMAL(18,4), col_numeric NUMERIC(18,4), col_char CHAR(10),
    col_varchar_50 VARCHAR(50), col_varchar_100 VARCHAR(100), col_varchar_255 VARCHAR(255),
    col_text TEXT, col_mediumtext MEDIUMTEXT, col_binary BINARY(8),
    col_varbinary VARBINARY(16), col_blob BLOB, col_date DATE,
    col_datetime DATETIME, col_timestamp TIMESTAMP NULL, col_time TIME,
    col_boolean BOOLEAN, col_tinyint_bool TINYINT(1),
    col_enum ENUM('new','processing','done','failed'), col_set SET('a','b','c'),
    col_json JSON, user_name VARCHAR(64), email VARCHAR(128), phone VARCHAR(32),
    address VARCHAR(255), city VARCHAR(64), country VARCHAR(64), postal_code VARCHAR(20),
    amount DECIMAL(18,4), balance DECIMAL(18,4), credit_limit DECIMAL(18,4),
    status VARCHAR(20), category VARCHAR(30), priority SMALLINT, score DOUBLE,
    created_at DATETIME, updated_at TIMESTAMP NULL, deleted TINYINT NOT NULL DEFAULT 0,
    dt DATE NOT NULL,
    KEY idx_performance_record_id (record_id), KEY idx_performance_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC
PARTITION BY RANGE COLUMNS(dt) (
    PARTITION p20260501 VALUES LESS THAN ('2026-05-02'),
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
);

INSERT INTO consilens_demo.consilens_performance_demo_table
SELECT
    CONCAT('REC',LPAD(n,10,'0')), MOD(n,100), MOD(n,30000), n*3, n*10, n*1000003,
    2147483648+n, CAST(MOD(n,1000)+0.125 AS FLOAT), CAST(MOD(n,100000)+0.25 AS DOUBLE),
    CAST(ROUND(MOD(n,100000)/100+0.1234,4) AS DECIMAL(18,4)),
    CAST(ROUND(MOD(n,100000)/50+0.5678,4) AS DECIMAL(18,4)),
    CONCAT('C',LPAD(n,9,'0')), CONCAT('v50_',LPAD(n,5,'0')),
    CONCAT('v100_',LPAD(n,5,'0'),'_stable'), CONCAT('v255_',LPAD(n,5,'0'),'_stable_payload'),
    CONCAT('text-',LPAD(n,5,'0')), CONCAT('mediumtext-',LPAD(n,5,'0'),'-',REPEAT('x',MOD(n,32))),
    UNHEX(LPAD(HEX(n),16,'0')), UNHEX(LPAD(HEX(n*17),32,'0')), UNHEX(LPAD(HEX(n*31),32,'0')),
    DATE_ADD('2026-05-01',INTERVAL MOD(n,7) DAY),
    DATE_ADD('2026-05-01 00:00:00',INTERVAL MOD(n,10000) SECOND),
    DATE_ADD('2026-05-01 00:00:00',INTERVAL MOD(n,10000) SECOND),
    SEC_TO_TIME(MOD(n,86400)), MOD(n,2)=0, MOD(n,2),
    CASE MOD(n,4) WHEN 0 THEN 'new' WHEN 1 THEN 'processing' WHEN 2 THEN 'done' ELSE 'failed' END,
    CASE MOD(n,3) WHEN 0 THEN 'a,b' WHEN 1 THEN 'b' ELSE 'c' END,
    JSON_OBJECT('value',CONCAT('json_',LPAD(n,5,'0'))),
    CONCAT('user_',LPAD(n,5,'0')), CONCAT('user_',LPAD(n,5,'0'),'@example.com'),
    CONCAT('+861380',LPAD(n,6,'0')), CONCAT('No.',n,' Consilens Road'),
    CASE MOD(n,4) WHEN 0 THEN 'Shanghai' WHEN 1 THEN 'Beijing' WHEN 2 THEN 'Shenzhen' ELSE 'Hangzhou' END,
    'CN', LPAD(MOD(n,1000000),6,'0'),
    CAST(ROUND(10+MOD(n,5000)/10,4) AS DECIMAL(18,4)),
    CAST(ROUND(1000+MOD(n,8000)/10,4) AS DECIMAL(18,4)),
    CAST(ROUND(5000+MOD(n,3000)/10,4) AS DECIMAL(18,4)),
    CASE MOD(n,4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
    CASE MOD(n,5) WHEN 0 THEN 'retail' WHEN 1 THEN 'finance' WHEN 2 THEN 'logistics' WHEN 3 THEN 'manufacturing' ELSE 'public' END,
    MOD(n,5)+1, CAST(MOD(n,100)+0.5 AS DOUBLE),
    DATE_ADD('2026-05-01 00:00:00',INTERVAL MOD(n,10000) SECOND),
    DATE_ADD('2026-05-01 00:05:00',INTERVAL MOD(n,10000) SECOND),
    IF(MOD(n,10)=0,1,0), DATE('2026-05-01')
FROM consilens_seq;

-- =========================================================
-- mydb.users
-- =========================================================

DROP TABLE IF EXISTS mydb.users;
CREATE TABLE mydb.users (
    id INT NOT NULL PRIMARY KEY, name VARCHAR(100), email VARCHAR(128) NOT NULL,
    phone VARCHAR(32), status VARCHAR(20), created_at DATETIME,
    KEY idx_users_email (email), KEY idx_users_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO mydb.users
SELECT n, CONCAT('user_',LPAD(n,5,'0')), CONCAT('user_',LPAD(n,5,'0'),'@example.com'),
    CONCAT('+861380',LPAD(n,6,'0')),
    CASE MOD(n,3) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' ELSE 'pending' END,
    DATE_ADD('2026-01-01 00:00:00',INTERVAL MOD(n,10000) SECOND)
FROM consilens_seq;

-- =========================================================
-- mydb.orders
-- =========================================================

DROP TABLE IF EXISTS mydb.orders_backup;
DROP TABLE IF EXISTS mydb.orders;
CREATE TABLE mydb.orders (
    order_id BIGINT NOT NULL PRIMARY KEY, customer_id INT NOT NULL,
    amount DECIMAL(18,4) NOT NULL, status VARCHAR(20) NOT NULL, created_at DATETIME NOT NULL,
    KEY idx_orders_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO mydb.orders
SELECT n, 100000+MOD(n,500), CAST(ROUND(20+MOD(n,10000)/20,4) AS DECIMAL(18,4)),
    CASE MOD(n,4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END,
    DATE_ADD('2025-01-01 00:00:00',INTERVAL MOD(n,365) DAY)
FROM consilens_seq;

CREATE TABLE mydb.orders_backup LIKE mydb.orders;
INSERT INTO mydb.orders_backup SELECT * FROM mydb.orders;

-- =========================================================
-- production.fact_orders
-- =========================================================

DROP TABLE IF EXISTS production.fact_orders;
CREATE TABLE production.fact_orders (
    order_id BIGINT NOT NULL PRIMARY KEY, customer_id INT NOT NULL, product_id INT NOT NULL,
    quantity INT NOT NULL, unit_price DECIMAL(18,4) NOT NULL, total_amount DECIMAL(18,4) NOT NULL,
    order_date DATE NOT NULL, status VARCHAR(20) NOT NULL, created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL, KEY idx_fact_orders_order_date (order_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO production.fact_orders
SELECT order_id, customer_id, product_id, quantity, unit_price,
    CAST(quantity*unit_price AS DECIMAL(18,4)), order_date, status, created_at, updated_at
FROM (
    SELECT n AS order_id, 100000+MOD(n,500) AS customer_id, 200000+MOD(n,1000) AS product_id,
        1+MOD(n,10) AS quantity, CAST(ROUND(5+MOD(n,2000)/10,4) AS DECIMAL(18,4)) AS unit_price,
        DATE_ADD('2026-05-01',INTERVAL MOD(n,30) DAY) AS order_date,
        CASE MOD(n,4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END AS status,
        DATE_ADD('2026-05-01 00:00:00',INTERVAL MOD(n,10000) SECOND) AS created_at,
        DATE_ADD('2026-05-01 00:10:00',INTERVAL MOD(n,10000) SECOND) AS updated_at
    FROM consilens_seq
) s;

-- =========================================================
-- cleanup and validation
-- =========================================================

DROP TABLE IF EXISTS consilens_digits;
DROP TABLE IF EXISTS consilens_seq;

SELECT 'mysql.consilens_demo.consilens_performance_demo_table' AS check_name,
    COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(record_id) AS min_record_id, MAX(record_id) AS max_record_id,
    ROUND(SUM(amount),4) AS amount_sum
FROM consilens_demo.consilens_performance_demo_table;

SELECT 'mysql.mydb.users' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(id) AS min_id, MAX(id) AS max_id FROM mydb.users;

SELECT 'mysql.mydb.orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(amount),4) AS amount_sum FROM mydb.orders;

SELECT 'mysql.production.fact_orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(total_amount),4) AS total_amount_sum FROM production.fact_orders;
