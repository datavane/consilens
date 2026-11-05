-- ClickHouse seed data for mysql-clickhouse comparison test
-- Target row count: 10000 rows in each table
-- Contains intentional inconsistencies vs MySQL source for difference detection
--
-- 不一致设计：
--   1. MISMATCH: record_id='REC0000000001' 的 amount 被修改
--   2. MISMATCH: record_id='REC0000000002' 的 status 被修改
--   3. SOURCE_MISSING: 目标端多出 record_id='REC_EXTRA_001'
--   4. TARGET_MISSING: 目标端缺少 record_id='REC0000000005'（跳过 n=5）
--
-- Usage:
--   clickhouse-client --host localhost --port 9000 --user default \
--     --password "$CLICKHOUSE_PASSWORD" < examples/mysql-clickhouse/load-clickhouse.sql

-- =========================================================
-- Create database
-- =========================================================

CREATE DATABASE IF NOT EXISTS consilens_demo;

-- =========================================================
-- sequence helper using generateSeries
-- =========================================================

CREATE TABLE IF NOT EXISTS consilens_demo.consilens_digits (d UInt8) ENGINE = Memory;
INSERT INTO consilens_demo.consilens_digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

CREATE TABLE IF NOT EXISTS consilens_demo.consilens_seq (n UInt32) ENGINE = Memory;
INSERT INTO consilens_demo.consilens_seq
SELECT ones.d + tens.d * 10 + hundreds.d * 100 + thousands.d * 1000 + 1 AS n
FROM consilens_demo.consilens_digits ones
CROSS JOIN consilens_demo.consilens_digits tens
CROSS JOIN consilens_demo.consilens_digits hundreds
CROSS JOIN consilens_demo.consilens_digits thousands
WHERE ones.d + tens.d * 10 + hundreds.d * 100 + thousands.d * 1000 < 10000
ORDER BY n;

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.consilens_performance_demo_table;

CREATE TABLE consilens_demo.consilens_performance_demo_table (
    record_id String,
    col_tinyint Int8,
    col_smallint Int16,
    col_mediumint Int32,
    col_int Int32,
    col_bigint Int64,
    col_unsigned_int UInt64,
    col_float Float32,
    col_double Float64,
    col_decimal Decimal(18,4),
    col_numeric Decimal(18,4),
    col_char FixedString(10),
    col_varchar_50 String,
    col_varchar_100 String,
    col_varchar_255 String,
    col_text String,
    col_mediumtext String,
    col_binary String,
    col_varbinary String,
    col_blob String,
    col_date Date,
    col_datetime DateTime,
    col_timestamp DateTime,
    col_time String,
    col_boolean UInt8,
    col_tinyint_bool UInt8,
    col_enum Enum8('new' = 1, 'processing' = 2, 'done' = 3, 'failed' = 4),
    col_set String,
    col_json String,
    user_name String,
    email String,
    phone String,
    address String,
    city String,
    country String,
    postal_code String,
    amount Decimal(18,4),
    balance Decimal(18,4),
    credit_limit Decimal(18,4),
    status String,
    category String,
    priority Int16,
    score Float64,
    created_at DateTime,
    updated_at DateTime,
    deleted UInt8 DEFAULT 0,
    dt Date
) ENGINE = MergeTree()
ORDER BY record_id
PARTITION BY toYYYYMM(dt);

-- 插入数据（跳过 n=5 制造 TARGET_MISSING）
INSERT INTO consilens_demo.consilens_performance_demo_table (
    record_id, col_tinyint, col_smallint, col_mediumint, col_int, col_bigint,
    col_unsigned_int, col_float, col_double, col_decimal, col_numeric,
    col_char, col_varchar_50, col_varchar_100, col_varchar_255, col_text, col_mediumtext,
    col_binary, col_varbinary, col_blob, col_date, col_datetime, col_timestamp, col_time,
    col_boolean, col_tinyint_bool, col_enum, col_set, col_json,
    user_name, email, phone, address, city, country, postal_code,
    amount, balance, credit_limit, status, category, priority, score,
    created_at, updated_at, deleted, dt
)
SELECT
    concat('REC', lpad(toString(n), 10, '0')) AS record_id,
    toInt8(modulo(n, 100)) AS col_tinyint,
    toInt16(modulo(n, 30000)) AS col_smallint,
    toInt32(n * 3) AS col_mediumint,
    toInt32(n * 10) AS col_int,
    toInt64(n) * 1000003 AS col_bigint,
    toUInt64(2147483648 + n) AS col_unsigned_int,
    toFloat32(modulo(n, 1000) + 0.125) AS col_float,
    toFloat64(modulo(n, 100000) + 0.25) AS col_double,
    toDecimal64(toString(ROUND(modulo(n, 100000) / 100.0 + 0.1234, 4)), 4) AS col_decimal,
    toDecimal64(toString(ROUND(modulo(n, 100000) / 50.0 + 0.5678, 4)), 4) AS col_numeric,
    concat('C', lpad(toString(n), 9, '0')) AS col_char,
    concat('v50_', lpad(toString(n), 5, '0')) AS col_varchar_50,
    concat('v100_', lpad(toString(n), 5, '0'), '_stable') AS col_varchar_100,
    concat('v255_', lpad(toString(n), 5, '0'), '_stable_payload') AS col_varchar_255,
    concat('text-', lpad(toString(n), 5, '0')) AS col_text,
    concat('mediumtext-', lpad(toString(n), 5, '0'), '-', repeat('x', modulo(n, 32))) AS col_mediumtext,
    upper(lpad(hex(toUInt64(n)), 16, '0')) AS col_binary,
    upper(lpad(hex(toUInt64(n * 17)), 32, '0')) AS col_varbinary,
    upper(lpad(hex(toUInt64(n * 31)), 32, '0')) AS col_blob,
    toDate('2026-05-01') + modulo(n, 7) AS col_date,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS col_datetime,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS col_timestamp,
    concat(lpad(toString(intDiv(modulo(n, 86400), 3600)), 2, '0'), ':', lpad(toString(intDiv(modulo(modulo(n, 86400), 3600), 60)), 2, '0'), ':', lpad(toString(modulo(modulo(n, 86400), 60)), 2, '0')) AS col_time,
    if(modulo(n, 2) = 0, 1, 0) AS col_boolean,
    modulo(n, 2) AS col_tinyint_bool,
    CASE modulo(n, 4) WHEN 0 THEN 'new' WHEN 1 THEN 'processing' WHEN 2 THEN 'done' ELSE 'failed' END AS col_enum,
    CASE modulo(n, 3) WHEN 0 THEN 'a,b' WHEN 1 THEN 'b' ELSE 'c' END AS col_set,
    concat('{"value": "json_', lpad(toString(n), 5, '0'), '"}') AS col_json,
    concat('user_', lpad(toString(n), 5, '0')) AS user_name,
    concat('user_', lpad(toString(n), 5, '0'), '@example.com') AS email,
    concat('+861380', lpad(toString(n), 6, '0')) AS phone,
    concat('No.', toString(n), ' Consilens Road') AS address,
    CASE modulo(n, 4) WHEN 0 THEN 'Shanghai' WHEN 1 THEN 'Beijing' WHEN 2 THEN 'Shenzhen' ELSE 'Hangzhou' END AS city,
    'CN' AS country,
    lpad(toString(modulo(n, 1000000)), 6, '0') AS postal_code,
    toDecimal64(toString(ROUND(10 + modulo(n, 5000) / 10.0, 4)), 4) AS amount,
    toDecimal64(toString(ROUND(1000 + modulo(n, 8000) / 10.0, 4)), 4) AS balance,
    toDecimal64(toString(ROUND(5000 + modulo(n, 3000) / 10.0, 4)), 4) AS credit_limit,
    CASE modulo(n, 4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END AS status,
    CASE modulo(n, 5) WHEN 0 THEN 'retail' WHEN 1 THEN 'finance' WHEN 2 THEN 'logistics' WHEN 3 THEN 'manufacturing' ELSE 'public' END AS category,
    toInt16(modulo(n, 5) + 1) AS priority,
    toFloat64(modulo(n, 100) + 0.5) AS score,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS created_at,
    toDateTime('2026-05-01 00:05:00') + modulo(n, 10000) AS updated_at,
    if(modulo(n, 10) = 0, 1, 0) AS deleted,
    toDate('2026-05-01') AS dt
FROM consilens_demo.consilens_seq
WHERE n != 5;  -- TARGET_MISSING: 跳过 n=5

-- =========================================================
-- 制造 MISMATCH: 修改 record_id='REC0000000001' 的 amount
-- =========================================================

ALTER TABLE consilens_demo.consilens_performance_demo_table
UPDATE amount = 99999.9999
WHERE record_id = 'REC0000000001' SETTINGS mutations_sync = 1;

-- =========================================================
-- 制造 MISMATCH: 修改 record_id='REC0000000002' 的 status
-- =========================================================

ALTER TABLE consilens_demo.consilens_performance_demo_table
UPDATE status = 'modified_status'
WHERE record_id = 'REC0000000002' SETTINGS mutations_sync = 1;

-- =========================================================
-- 制造 SOURCE_MISSING: 目标端多出一条记录
-- =========================================================

INSERT INTO consilens_demo.consilens_performance_demo_table (
    record_id, col_tinyint, col_smallint, col_mediumint, col_int, col_bigint,
    col_unsigned_int, col_float, col_double, col_decimal, col_numeric,
    col_char, col_varchar_50, col_varchar_100, col_varchar_255, col_text, col_mediumtext,
    col_binary, col_varbinary, col_blob, col_date, col_datetime, col_timestamp, col_time,
    col_boolean, col_tinyint_bool, col_enum, col_set, col_json,
    user_name, email, phone, address, city, country, postal_code,
    amount, balance, credit_limit, status, category, priority, score,
    created_at, updated_at, deleted, dt
) VALUES (
    'REC_EXTRA_001', 1, 1, 3, 10, 1000003, 2147483649,
    1.125, 1.25, 0.1334, 0.5878,
    'C000000001', 'v50_00001', 'v100_00001_stable', 'v255_00001_stable_payload',
    'text-00001', 'mediumtext-00001-x', '\x00\x00\x00\x00\x00\x00\x00\x01', '\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x11', '\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x1f',
    toDate('2026-05-02'), toDateTime('2026-05-01 00:00:01'), toDateTime('2026-05-01 00:00:01'), '00:00:01',
    0, 1, 'processing', 'b', '{"value": "json_00001"}',
    'user_00001', 'user_00001@example.com', '+861380000001', 'No.1 Consilens Road',
    'Beijing', 'CN', '000001', 10.1000, 1000.1000, 5000.1000,
    'inactive', 'finance', 2, 1.5,
    toDateTime('2026-05-01 00:00:01'), toDateTime('2026-05-01 00:05:01'), 0, toDate('2026-05-01')
);

-- =========================================================
-- daily_order_summary (聚合表，用于 detail-to-aggregate 测试)
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.daily_order_summary;

CREATE TABLE consilens_demo.daily_order_summary (
    biz_date Date,
    status String,
    order_count UInt64,
    total_amount Decimal(38,4),
    updated_at DateTime
) ENGINE = MergeTree()
ORDER BY (biz_date, status);

INSERT INTO consilens_demo.daily_order_summary
SELECT
    toDate(created_at) AS biz_date,
    status,
    COUNT(*) AS order_count,
    toDecimal64(SUM(amount), 4) AS total_amount,
    MAX(updated_at) AS updated_at
FROM consilens_demo.consilens_performance_demo_table
WHERE deleted = 0
GROUP BY toDate(created_at), status;

-- =========================================================
-- fact_orders (大表，用于 large-table 测试)
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.fact_orders;

CREATE TABLE consilens_demo.fact_orders (
    order_id UInt64,
    customer_id UInt32,
    product_id UInt32,
    quantity UInt32,
    unit_price Decimal(18,4),
    total_amount Decimal(18,4),
    order_date Date,
    status String,
    created_at DateTime,
    updated_at DateTime
) ENGINE = MergeTree()
ORDER BY order_id
PARTITION BY toYYYYMM(order_date);

INSERT INTO consilens_demo.fact_orders
SELECT
    n AS order_id,
    100000 + modulo(n, 500) AS customer_id,
    200000 + modulo(n, 1000) AS product_id,
    1 + modulo(n, 10) AS quantity,
    toDecimal64(toString(ROUND(5 + modulo(n, 2000) / 10.0, 4)), 4) AS unit_price,
    toDecimal64((1 + modulo(n, 10)) * ROUND(5 + modulo(n, 2000) / 10.0, 4), 4) AS total_amount,
    toDate('2026-05-01') + modulo(n, 30) AS order_date,
    CASE modulo(n, 4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END AS status,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS created_at,
    toDateTime('2026-05-01 00:10:00') + modulo(n, 10000) AS updated_at
FROM consilens_demo.consilens_seq;

-- =========================================================
-- users (用于 mapped-checksum 测试)
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.users;

CREATE TABLE consilens_demo.users (
    id UInt32,
    name String,
    email String,
    phone String,
    status String,
    created_at DateTime
) ENGINE = MergeTree()
ORDER BY id;

INSERT INTO consilens_demo.users
SELECT
    n AS id,
    concat('user_', lpad(toString(n), 5, '0')) AS name,
    concat('user_', lpad(toString(n), 5, '0'), '@example.com') AS email,
    concat('+861380', lpad(toString(n), 6, '0')) AS phone,
    CASE modulo(n, 3) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' ELSE 'pending' END AS status,
    toDateTime('2026-01-01 00:00:00') + modulo(n, 10000) AS created_at
FROM consilens_demo.consilens_seq;

-- 制造 MISMATCH: 修改 id=1 的 email
ALTER TABLE consilens_demo.users UPDATE email = 'modified@example.com' WHERE id = 1 SETTINGS mutations_sync = 1;

-- =========================================================
-- orders / orders_backup (用于 same-db-join 测试)
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.orders;
DROP TABLE IF EXISTS consilens_demo.orders_backup;

CREATE TABLE consilens_demo.orders (
    order_id UInt64,
    customer_id UInt32,
    amount Decimal(18,4),
    status String,
    created_at DateTime
) ENGINE = MergeTree()
ORDER BY order_id;

INSERT INTO consilens_demo.orders
SELECT
    n AS order_id,
    100000 + modulo(n, 500) AS customer_id,
    toDecimal64(toString(ROUND(20 + modulo(n, 10000) / 20.0, 4)), 4) AS amount,
    CASE modulo(n, 4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END AS status,
    toDateTime('2025-01-01 00:00:00') + modulo(n, 365) AS created_at
FROM consilens_demo.consilens_seq;

CREATE TABLE consilens_demo.orders_backup AS consilens_demo.orders;
INSERT INTO consilens_demo.orders_backup SELECT * FROM consilens_demo.orders;

-- 制造 MISMATCH: orders_backup 修改某订单 amount
ALTER TABLE consilens_demo.orders_backup UPDATE amount = 99999.9999 WHERE order_id = 1 SETTINGS mutations_sync = 1;

-- 制造 TARGET_MISSING: source 端 orders 插入额外订单（target 端 orders_backup 缺失）
INSERT INTO consilens_demo.orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 100500, 999.99, 'paid', toDateTime('2025-06-15 12:00:00'));

-- =========================================================
-- cleanup helper tables
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.consilens_digits;
DROP TABLE IF EXISTS consilens_demo.consilens_seq;

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'clickhouse.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows, 10000 AS expected_rows,
       min(record_id) AS min_record_id, max(record_id) AS max_record_id,
       round(sum(amount), 4) AS amount_sum
FROM consilens_demo.consilens_performance_demo_table;

SELECT 'clickhouse.consilens_demo.daily_order_summary' AS check_name,
       COUNT(*) AS actual_groups, round(sum(total_amount), 4) AS total_amount_sum
FROM consilens_demo.daily_order_summary;

SELECT 'clickhouse.consilens_demo.fact_orders' AS check_name,
       COUNT(*) AS actual_rows, 10000 AS expected_rows,
       round(sum(total_amount), 4) AS total_amount_sum
FROM consilens_demo.fact_orders;

SELECT 'clickhouse.consilens_demo.users' AS check_name,
       COUNT(*) AS actual_rows, 10000 AS expected_rows,
       min(id) AS min_id, max(id) AS max_id
FROM consilens_demo.users;

SELECT 'clickhouse.consilens_demo.orders' AS check_name,
       COUNT(*) AS actual_rows, round(sum(amount), 4) AS amount_sum
FROM consilens_demo.orders;

SELECT 'clickhouse.consilens_demo.orders_backup' AS check_name,
       COUNT(*) AS actual_rows, round(sum(amount), 4) AS amount_sum
FROM consilens_demo.orders_backup;
