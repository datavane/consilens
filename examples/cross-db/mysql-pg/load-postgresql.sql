-- PostgreSQL seed data for mysql-pg comparison test
-- Target row count: 10000 rows in each table
-- Contains intentional inconsistencies vs MySQL source for difference detection
--
-- 不一致设计：
--   1. MISMATCH: record_id='REC0000000001' 的 amount 被修改
--   2. MISMATCH: record_id='REC0000000002' 的 status 被修改
--   3. SOURCE_MISSING: 目标端多出 record_id='REC_EXTRA_001'
--   4. TARGET_MISSING: 目标端缺少 record_id='REC0000000005'（跳过 n=5）

-- =========================================================
-- create target database and connect to it
-- (run through psql; the script connects to the default
--  "postgres" database first, then switches below)
-- =========================================================

SELECT 'CREATE DATABASE consilens_demo'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'consilens_demo')\gexec

\connect consilens_demo

SET TIME ZONE 'Asia/Shanghai';

-- =========================================================
-- cleanup
-- =========================================================

DROP TABLE IF EXISTS public.daily_order_summary;
DROP TABLE IF EXISTS public.fact_orders;
DROP TABLE IF EXISTS public.orders_backup;
DROP TABLE IF EXISTS public.orders;
DROP TABLE IF EXISTS public.consilens_performance_demo_table;
DROP TABLE IF EXISTS public.users;

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

CREATE TABLE public.consilens_performance_demo_table (
                                                record_id VARCHAR(16) NOT NULL,
                                                col_tinyint SMALLINT,
                                                col_smallint SMALLINT,
                                                col_mediumint INTEGER,
                                                col_int INTEGER,
                                                col_bigint BIGINT,
                                                col_unsigned_int BIGINT,
                                                col_float REAL,
                                                col_double DOUBLE PRECISION,
                                                col_decimal NUMERIC(18,4),
                                                col_numeric NUMERIC(18,4),
                                                col_char CHAR(10),
                                                col_varchar_50 VARCHAR(50),
                                                col_varchar_100 VARCHAR(100),
                                                col_varchar_255 VARCHAR(255),
                                                col_text TEXT,
                                                col_mediumtext TEXT,
                                                col_binary BYTEA,
                                                col_varbinary BYTEA,
                                                col_blob BYTEA,
                                                col_date DATE,
                                                col_datetime TIMESTAMP,
                                                col_timestamp TIMESTAMP,
                                                col_time TIME,
                                                col_boolean BOOLEAN,
                                                col_tinyint_bool SMALLINT,
                                                col_enum TEXT,
                                                col_set TEXT,
                                                col_json JSONB,
                                                user_name VARCHAR(64),
                                                email VARCHAR(128),
                                                phone VARCHAR(32),
                                                address VARCHAR(255),
                                                city VARCHAR(64),
                                                country VARCHAR(64),
                                                postal_code VARCHAR(20),
                                                amount NUMERIC(18,4),
                                                balance NUMERIC(18,4),
                                                credit_limit NUMERIC(18,4),
                                                status VARCHAR(20),
                                                category VARCHAR(30),
                                                priority SMALLINT,
                                                score DOUBLE PRECISION,
                                                created_at TIMESTAMP,
                                                updated_at TIMESTAMP,
                                                deleted SMALLINT NOT NULL DEFAULT 0,
                                                dt DATE NOT NULL
);

CREATE INDEX idx_consilens_perf_demo_record_id
    ON public.consilens_performance_demo_table(record_id);

CREATE INDEX idx_consilens_perf_demo_dt
    ON public.consilens_performance_demo_table(dt);

-- =========================================================
-- insert consilens_performance_demo_table (跳过 n=5 制造 TARGET_MISSING)
-- =========================================================

INSERT INTO public.consilens_performance_demo_table (
    record_id,
    col_tinyint,
    col_smallint,
    col_mediumint,
    col_int,
    col_bigint,
    col_unsigned_int,
    col_float,
    col_double,
    col_decimal,
    col_numeric,
    col_char,
    col_varchar_50,
    col_varchar_100,
    col_varchar_255,
    col_text,
    col_mediumtext,
    col_binary,
    col_varbinary,
    col_blob,
    col_date,
    col_datetime,
    col_timestamp,
    col_time,
    col_boolean,
    col_tinyint_bool,
    col_enum,
    col_set,
    col_json,
    user_name,
    email,
    phone,
    address,
    city,
    country,
    postal_code,
    amount,
    balance,
    credit_limit,
    status,
    category,
    priority,
    score,
    created_at,
    updated_at,
    deleted,
    dt
)
SELECT
    'REC' || LPAD(n::TEXT, 10, '0') AS record_id,
    (n % 100)::SMALLINT AS col_tinyint,
    (n % 30000)::SMALLINT AS col_smallint,
    (n * 3)::INTEGER AS col_mediumint,
    (n * 10)::INTEGER AS col_int,
    (n::BIGINT * 1000003) AS col_bigint,
    (CAST(2147483648 AS BIGINT) + n::BIGINT) AS col_unsigned_int,
    CAST((n % 1000) + 0.125 AS REAL) AS col_float,
    CAST((n % 100000) + 0.25 AS DOUBLE PRECISION) AS col_double,
    CAST(ROUND((n % 100000)::NUMERIC / 100 + 0.1234, 4) AS NUMERIC(18,4)) AS col_decimal,
    CAST(ROUND((n % 100000)::NUMERIC / 50 + 0.5678, 4) AS NUMERIC(18,4)) AS col_numeric,
    'C' || LPAD(n::TEXT, 9, '0') AS col_char,
    'v50_' || LPAD(n::TEXT, 5, '0') AS col_varchar_50,
    'v100_' || LPAD(n::TEXT, 5, '0') || '_stable' AS col_varchar_100,
    'v255_' || LPAD(n::TEXT, 5, '0') || '_stable_payload' AS col_varchar_255,
    'text-' || LPAD(n::TEXT, 5, '0') AS col_text,
    'mediumtext-' || LPAD(n::TEXT, 5, '0') || '-' || REPEAT('x', n % 32) AS col_mediumtext,
    DECODE(LPAD(TO_HEX(n::BIGINT), 16, '0'), 'hex') AS col_binary,
    DECODE(LPAD(TO_HEX(n::BIGINT * 17), 32, '0'), 'hex') AS col_varbinary,
    DECODE(LPAD(TO_HEX(n::BIGINT * 31), 32, '0'), 'hex') AS col_blob,
    DATE '2026-05-01' + (n % 7) AS col_date,
    TIMESTAMP '2026-05-01 00:00:00' + ((n % 10000) * INTERVAL '1 second') AS col_datetime,
    TIMESTAMP '2026-05-01 00:00:00' + ((n % 10000) * INTERVAL '1 second') AS col_timestamp,
    TIME '00:00:00' + ((n % 86400) * INTERVAL '1 second') AS col_time,
    (n % 2 = 0) AS col_boolean,
    (n % 2)::SMALLINT AS col_tinyint_bool,
    CASE n % 4
    WHEN 0 THEN 'new'
    WHEN 1 THEN 'processing'
    WHEN 2 THEN 'done'
    ELSE 'failed'
    END AS col_enum,
    CASE n % 3
    WHEN 0 THEN 'a,b'
    WHEN 1 THEN 'b'
    ELSE 'c'
    END AS col_set,
    JSONB_BUILD_OBJECT('value', 'json_' || LPAD(n::TEXT, 5, '0')) AS col_json,
    'user_' || LPAD(n::TEXT, 5, '0') AS user_name,
    'user_' || LPAD(n::TEXT, 5, '0') || '@example.com' AS email,
    '+861380' || LPAD(n::TEXT, 6, '0') AS phone,
    'No.' || n || ' Consilens Road' AS address,
    CASE n % 4
    WHEN 0 THEN 'Shanghai'
    WHEN 1 THEN 'Beijing'
    WHEN 2 THEN 'Shenzhen'
    ELSE 'Hangzhou'
    END AS city,
    'CN' AS country,
    LPAD((n % 1000000)::TEXT, 6, '0') AS postal_code,
    CAST(ROUND(10 + (n % 5000)::NUMERIC / 10, 4) AS NUMERIC(18,4)) AS amount,
    CAST(ROUND(1000 + (n % 8000)::NUMERIC / 10, 4) AS NUMERIC(18,4)) AS balance,
    CAST(ROUND(5000 + (n % 3000)::NUMERIC / 10, 4) AS NUMERIC(18,4)) AS credit_limit,
    CASE n % 4
    WHEN 0 THEN 'active'
    WHEN 1 THEN 'inactive'
    WHEN 2 THEN 'pending'
    ELSE 'blocked'
    END AS status,
    CASE n % 5
    WHEN 0 THEN 'retail'
    WHEN 1 THEN 'finance'
    WHEN 2 THEN 'logistics'
    WHEN 3 THEN 'manufacturing'
    ELSE 'public'
    END AS category,
    (n % 5 + 1)::SMALLINT AS priority,
    CAST((n % 100) + 0.5 AS DOUBLE PRECISION) AS score,
    TIMESTAMP '2026-05-01 00:00:00' + ((n % 10000) * INTERVAL '1 second') AS created_at,
    TIMESTAMP '2026-05-01 00:05:00' + ((n % 10000) * INTERVAL '1 second') AS updated_at,
    CASE WHEN n % 10 = 0 THEN 1 ELSE 0 END AS deleted,
    DATE '2026-05-01' AS dt
FROM GENERATE_SERIES(1, 10000) AS seq(n)
WHERE n != 5;  -- TARGET_MISSING: 跳过 n=5

-- =========================================================
-- 制造 MISMATCH: 修改 record_id='REC0000000001' 的 amount
-- =========================================================

UPDATE public.consilens_performance_demo_table
SET amount = 99999.9999
WHERE record_id = 'REC0000000001';

-- =========================================================
-- 制造 MISMATCH: 修改 record_id='REC0000000002' 的 status
-- =========================================================

UPDATE public.consilens_performance_demo_table
SET status = 'modified_status'
WHERE record_id = 'REC0000000002';

-- =========================================================
-- 制造 SOURCE_MISSING: 目标端多出一条记录
-- =========================================================

INSERT INTO public.consilens_performance_demo_table (
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
    'text-00001', 'mediumtext-00001-x', '\x0000000000000001', '\x0000000000000011', '\x000000000000001f',
    '2026-05-02', '2026-05-01 00:00:01', '2026-05-01 00:00:01', '00:00:01',
    false, 1, 'processing', 'b', '{"value":"json_00001"}',
    'user_00001', 'user_00001@example.com', '+861380000001', 'No.1 Consilens Road',
    'Beijing', 'CN', '000001', 10.1000, 1000.1000, 5000.1000,
    'inactive', 'finance', 2, 1.5,
    '2026-05-01 00:00:01', '2026-05-01 00:05:01', 0, '2026-05-01'
);

-- =========================================================
-- daily_order_summary (聚合表，用于 detail-to-aggregate 测试)
-- =========================================================

CREATE TABLE public.daily_order_summary (
                                            biz_date DATE NOT NULL,
                                            status VARCHAR(20) NOT NULL,
                                            order_count BIGINT NOT NULL,
                                            total_amount NUMERIC(38,4) NOT NULL,
                                            updated_at TIMESTAMP,
                                            PRIMARY KEY (biz_date, status)
);

INSERT INTO public.daily_order_summary (
    biz_date,
    status,
    order_count,
    total_amount,
    updated_at
)
SELECT
    created_at::DATE AS biz_date,
    status,
    COUNT(*) AS order_count,
    CAST(SUM(amount) AS NUMERIC(38,4)) AS total_amount,
    MAX(updated_at) AS updated_at
FROM public.consilens_performance_demo_table
WHERE deleted = 0
GROUP BY created_at::DATE, status;

-- =========================================================
-- fact_orders (大表，用于 large-table 测试)
-- =========================================================

CREATE TABLE public.fact_orders (
    order_id BIGINT NOT NULL PRIMARY KEY,
    customer_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(18,4) NOT NULL,
    total_amount NUMERIC(18,4) NOT NULL,
    order_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO public.fact_orders (
    order_id, customer_id, product_id, quantity, unit_price, total_amount,
    order_date, status, created_at, updated_at
)
SELECT
    n AS order_id,
    100000 + (n % 500) AS customer_id,
    200000 + (n % 1000) AS product_id,
    1 + (n % 10) AS quantity,
    CAST(ROUND(5 + (n % 2000)::NUMERIC / 10, 4) AS NUMERIC(18,4)) AS unit_price,
    CAST((1 + (n % 10)) * ROUND(5 + (n % 2000)::NUMERIC / 10, 4) AS NUMERIC(18,4)) AS total_amount,
    DATE '2026-05-01' + (n % 30) AS order_date,
    CASE n % 4
        WHEN 0 THEN 'paid'
        WHEN 1 THEN 'created'
        WHEN 2 THEN 'shipped'
        ELSE 'closed'
    END AS status,
    TIMESTAMP '2026-05-01 00:00:00' + ((n % 10000) * INTERVAL '1 second') AS created_at,
    TIMESTAMP '2026-05-01 00:10:00' + ((n % 10000) * INTERVAL '1 second') AS updated_at
FROM GENERATE_SERIES(1, 10000) AS seq(n);

-- =========================================================
-- users (用于 mapped-checksum 测试)
-- =========================================================

CREATE TABLE public.users (
                              id INTEGER NOT NULL PRIMARY KEY,
                              name VARCHAR(100),
                              email VARCHAR(128) NOT NULL,
                              phone VARCHAR(32),
                              status VARCHAR(20),
                              created_at TIMESTAMP
);

CREATE INDEX idx_users_email ON public.users(email);
CREATE INDEX idx_users_created_at ON public.users(created_at);

INSERT INTO public.users (id, name, email, phone, status, created_at)
SELECT
    n AS id,
    'user_' || LPAD(n::TEXT, 5, '0') AS name,
    'user_' || LPAD(n::TEXT, 5, '0') || '@example.com' AS email,
    '+861380' || LPAD(n::TEXT, 6, '0') AS phone,
    CASE n % 3
    WHEN 0 THEN 'active'
    WHEN 1 THEN 'inactive'
    ELSE 'pending'
    END AS status,
    TIMESTAMP '2026-01-01 00:00:00' + ((n % 10000) * INTERVAL '1 second') AS created_at
FROM GENERATE_SERIES(1, 10000) AS seq(n);

-- 制造 MISMATCH: 修改 id=1 的 email
UPDATE public.users SET email = 'modified@example.com' WHERE id = 1;

-- =========================================================
-- orders / orders_backup (用于 same-db-join 测试)
-- =========================================================

CREATE TABLE public.orders (
    order_id BIGINT NOT NULL PRIMARY KEY,
    customer_id INT NOT NULL,
    amount NUMERIC(18,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

INSERT INTO public.orders (order_id, customer_id, amount, status, created_at)
SELECT
    n AS order_id,
    100000 + (n % 500) AS customer_id,
    CAST(ROUND(20 + (n % 10000)::NUMERIC / 20, 4) AS NUMERIC(18,4)) AS amount,
    CASE n % 4
        WHEN 0 THEN 'paid'
        WHEN 1 THEN 'created'
        WHEN 2 THEN 'shipped'
        ELSE 'closed'
    END AS status,
    TIMESTAMP '2025-01-01 00:00:00' + ((n % 365) * INTERVAL '1 day') AS created_at
FROM GENERATE_SERIES(1, 10000) AS seq(n);

CREATE TABLE public.orders_backup AS SELECT * FROM public.orders;

-- 制造 MISMATCH: orders_backup 修改某订单 amount
UPDATE public.orders_backup SET amount = 99999.9999 WHERE order_id = 1;

-- 制造 TARGET_MISSING: source 端 orders 插入额外订单（target 端 orders_backup 缺失）
INSERT INTO public.orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 100500, 999.99, 'paid', '2025-06-15 12:00:00');

-- =========================================================
-- analyze
-- =========================================================

ANALYZE public.consilens_performance_demo_table;
ANALYZE public.daily_order_summary;
ANALYZE public.fact_orders;
ANALYZE public.users;
ANALYZE public.orders;
ANALYZE public.orders_backup;

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'postgres.public.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(record_id) AS min_record_id,
       MAX(record_id) AS max_record_id,
       ROUND(SUM(amount)::NUMERIC, 4) AS amount_sum
FROM public.consilens_performance_demo_table;

SELECT 'postgres.public.daily_order_summary' AS check_name,
       COUNT(*) AS actual_groups,
       ROUND(SUM(total_amount)::NUMERIC, 4) AS total_amount_sum
FROM public.daily_order_summary;

SELECT 'postgres.public.fact_orders' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       ROUND(SUM(total_amount)::NUMERIC, 4) AS total_amount_sum
FROM public.fact_orders;

SELECT 'postgres.public.users' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(id) AS min_id,
       MAX(id) AS max_id
FROM public.users;

SELECT 'postgres.public.orders' AS check_name,
       COUNT(*) AS actual_rows,
       ROUND(SUM(amount)::NUMERIC, 4) AS amount_sum
FROM public.orders;

SELECT 'postgres.public.orders_backup' AS check_name,
       COUNT(*) AS actual_rows,
       ROUND(SUM(amount)::NUMERIC, 4) AS amount_sum
FROM public.orders_backup;
