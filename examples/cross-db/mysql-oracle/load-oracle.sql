-- Oracle seed data for mysql-oracle comparison test
-- Purpose: MySQL source has "correct" data; Oracle target has known differences
-- Connect as system user, then create application schema

ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD';
ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS';

-- =========================================================
-- Create application user (run as system/admin)
-- =========================================================

-- The application password is injected from the ORACLE_PASSWORD
-- environment variable. Run this script through a wrapper that
-- substitutes it, for example:
--   sed "s/__ORACLE_APP_PASSWORD__/${ORACLE_PASSWORD}/" \
--       examples/mysql-oracle/load-oracle.sql | sqlplus system/...@//127.0.0.1:1521/ORCL
-- DROP USER consilens CASCADE;
CREATE USER consilens IDENTIFIED BY __ORACLE_APP_PASSWORD__
    DEFAULT TABLESPACE users
    TEMPORARY TABLESPACE temp
    QUOTA UNLIMITED ON users;
GRANT CREATE SESSION TO consilens;
GRANT CREATE TABLE TO consilens;
GRANT CREATE SEQUENCE TO consilens;
GRANT CREATE TRIGGER TO consilens;

-- =========================================================
-- Connect as consilens user for the following operations
-- conn consilens/<ORACLE_PASSWORD>@//127.0.0.1:1521/ORCL
-- =========================================================

-- =========================================================
-- sequence helper: use CONNECT BY for number generation
-- =========================================================

-- =========================================================
-- consilens_performance_demo_table (Oracle target)
-- =========================================================

DROP TABLE consilens_performance_demo_table PURGE;

CREATE TABLE consilens_performance_demo_table (
    record_id VARCHAR2(16) NOT NULL, col_tinyint NUMBER(3), col_smallint NUMBER(5),
    col_mediumint NUMBER(7), col_int NUMBER(10), col_bigint NUMBER(19),
    col_unsigned_int NUMBER(10), col_float BINARY_FLOAT, col_double BINARY_DOUBLE,
    col_decimal NUMBER(18,4), col_numeric NUMBER(18,4), col_char CHAR(10),
    col_varchar_50 VARCHAR2(50), col_varchar_100 VARCHAR2(100), col_varchar_255 VARCHAR2(255),
    col_text CLOB, col_mediumtext CLOB, col_binary RAW(8),
    col_varbinary RAW(16), col_blob BLOB, col_date DATE,
    col_datetime TIMESTAMP, col_timestamp TIMESTAMP, col_time VARCHAR2(10),
    col_boolean NUMBER(1), col_tinyint_bool NUMBER(1),
    col_enum VARCHAR2(20), col_set VARCHAR2(20),
    col_json CLOB, user_name VARCHAR2(64), email VARCHAR2(128), phone VARCHAR2(32),
    address VARCHAR2(255), city VARCHAR2(64), country VARCHAR2(64), postal_code VARCHAR2(20),
    amount NUMBER(18,4), balance NUMBER(18,4), credit_limit NUMBER(18,4),
    status VARCHAR2(20), category VARCHAR2(30), priority NUMBER(5), score BINARY_DOUBLE,
    created_at TIMESTAMP, updated_at TIMESTAMP, deleted NUMBER(1) DEFAULT 0 NOT NULL,
    dt DATE NOT NULL,
    CONSTRAINT pk_perf_demo PRIMARY KEY (record_id)
);

-- Insert with TARGET_MISSING: skip n=5
INSERT INTO consilens_performance_demo_table
SELECT
    'REC' || LPAD(n, 10, '0'), MOD(n, 100), MOD(n, 30000), n * 3, n * 10, n * 1000003,
    2147483648 + n, MOD(n, 1000) + 0.125, MOD(n, 100000) + 0.25,
    ROUND(MOD(n, 100000) / 100 + 0.1234, 4),
    ROUND(MOD(n, 100000) / 50 + 0.5678, 4),
    'C' || LPAD(n, 9, '0'), 'v50_' || LPAD(n, 5, '0'),
    'v100_' || LPAD(n, 5, '0') || '_stable', 'v255_' || LPAD(n, 5, '0') || '_stable_payload',
    'text-' || LPAD(n, 5, '0'), 'mediumtext-' || LPAD(n, 5, '0') || '-' || RPAD('x', MOD(n, 32), 'x'),
    HEXTORAW(LPAD(TO_CHAR(n, 'fmXXXXXXXXXXXXXXXX'), 16, '0')), HEXTORAW(LPAD(TO_CHAR(n * 17, 'fmXXXXXXXXXXXXXXXX'), 32, '0')), HEXTORAW(LPAD(TO_CHAR(n * 31, 'fmXXXXXXXXXXXXXXXX'), 32, '0')),
    TO_DATE('2026-05-01', 'YYYY-MM-DD') + MOD(n, 7),
    TO_DATE('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400,
    TO_TIMESTAMP('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400,
    TO_CHAR(TRUNC(SYSDATE) + MOD(n, 86400) * INTERVAL '1' SECOND, 'HH24:MI:SS'), CASE MOD(n, 2) WHEN 0 THEN 1 ELSE 0 END, MOD(n, 2),
    CASE MOD(n, 4) WHEN 0 THEN 'new' WHEN 1 THEN 'processing' WHEN 2 THEN 'done' ELSE 'failed' END,
    CASE MOD(n, 3) WHEN 0 THEN 'a,b' WHEN 1 THEN 'b' ELSE 'c' END,
    '{"value": "json_' || LPAD(n, 5, '0') || '"}',
    'user_' || LPAD(n, 5, '0'), 'user_' || LPAD(n, 5, '0') || '@example.com',
    '+861380' || LPAD(n, 6, '0'), 'No.' || n || ' Consilens Road',
    CASE MOD(n, 4) WHEN 0 THEN 'Shanghai' WHEN 1 THEN 'Beijing' WHEN 2 THEN 'Shenzhen' ELSE 'Hangzhou' END,
    'CN', LPAD(MOD(n, 1000000), 6, '0'),
    ROUND(10 + MOD(n, 5000) / 10, 4),
    ROUND(1000 + MOD(n, 8000) / 10, 4),
    ROUND(5000 + MOD(n, 3000) / 10, 4),
    CASE MOD(n, 4) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked' END,
    CASE MOD(n, 5) WHEN 0 THEN 'retail' WHEN 1 THEN 'finance' WHEN 2 THEN 'logistics' WHEN 3 THEN 'manufacturing' ELSE 'public' END,
    MOD(n, 5) + 1, MOD(n, 100) + 0.5,
    TO_DATE('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400,
    TO_TIMESTAMP('2026-05-01 00:05:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400,
    CASE WHEN MOD(n, 10) = 0 THEN 1 ELSE 0 END, TO_DATE('2026-05-01', 'YYYY-MM-DD')
FROM (
    SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 10000
) seq
WHERE n != 5;
-- TARGET_MISSING: row REC0000000005 intentionally absent

COMMIT;

-- MISMATCH: modify specific records
UPDATE consilens_performance_demo_table SET amount = 99999.9999 WHERE record_id = 'REC0000000001';
UPDATE consilens_performance_demo_table SET status = 'modified_status' WHERE record_id = 'REC0000000002';
COMMIT;

-- SOURCE_MISSING: extra record in target
INSERT INTO consilens_performance_demo_table
(record_id, col_tinyint, col_int, col_decimal, amount, status, updated_at, dt)
VALUES ('REC_EXTRA_001', 1, 100, 100.1234, 5000.0000, 'extra', SYSTIMESTAMP, TO_DATE('2026-05-01', 'YYYY-MM-DD'));
COMMIT;

-- =========================================================
-- users table
-- =========================================================

DROP TABLE users PURGE;
CREATE TABLE users (
    id NUMBER(10) NOT NULL PRIMARY KEY, name VARCHAR2(100), email VARCHAR2(128) NOT NULL,
    phone VARCHAR2(32), status VARCHAR2(20), created_at DATE
);

INSERT INTO users
SELECT n, 'user_' || LPAD(n, 5, '0'), 'user_' || LPAD(n, 5, '0') || '@example.com',
    '+861380' || LPAD(n, 6, '0'),
    CASE MOD(n, 3) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' ELSE 'pending' END,
    TO_DATE('2026-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400
FROM (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 10000);
COMMIT;

-- MISMATCH: 修改 id=1 的 email
UPDATE users SET email = 'modified@example.com' WHERE id = 1;
COMMIT;

-- =========================================================
-- orders table
-- =========================================================

DROP TABLE orders_backup PURGE;
DROP TABLE orders PURGE;
CREATE TABLE orders (
    order_id NUMBER(19) NOT NULL PRIMARY KEY, customer_id NUMBER(10) NOT NULL,
    amount NUMBER(18,4) NOT NULL, status VARCHAR2(20) NOT NULL, created_at DATE NOT NULL
);

INSERT INTO orders
SELECT n, 100000 + MOD(n, 500), ROUND(20 + MOD(n, 10000) / 20, 4),
    CASE MOD(n, 4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END,
    TO_DATE('2025-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 365)
FROM (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 10000);
COMMIT;

CREATE TABLE orders_backup AS SELECT * FROM orders;
COMMIT;

-- MISMATCH: orders_backup 修改某订单 amount
UPDATE orders_backup SET amount = 99999.9999 WHERE order_id = 1;
COMMIT;

-- TARGET_MISSING: source 端 orders 插入额外订单（target 端 orders_backup 缺失）
INSERT INTO orders (order_id, customer_id, amount, status, created_at)
VALUES (10001, 100500, 999.99, 'paid', TO_DATE('2025-06-15 12:00:00', 'YYYY-MM-DD HH24:MI:SS'));
COMMIT;

-- =========================================================
-- fact_orders table
-- =========================================================

DROP TABLE fact_orders PURGE;
CREATE TABLE fact_orders (
    order_id NUMBER(19) NOT NULL PRIMARY KEY, customer_id NUMBER(10) NOT NULL, product_id NUMBER(10) NOT NULL,
    quantity NUMBER(10) NOT NULL, unit_price NUMBER(18,4) NOT NULL, total_amount NUMBER(18,4) NOT NULL,
    order_date DATE NOT NULL, status VARCHAR2(20) NOT NULL, created_at DATE NOT NULL,
    updated_at DATE NOT NULL
);

INSERT INTO fact_orders
SELECT order_id, customer_id, product_id, quantity, unit_price,
    quantity * unit_price, order_date, status, created_at, updated_at
FROM (
    SELECT n AS order_id, 100000 + MOD(n, 500) AS customer_id, 200000 + MOD(n, 1000) AS product_id,
        1 + MOD(n, 10) AS quantity, ROUND(5 + MOD(n, 2000) / 10, 4) AS unit_price,
        TO_DATE('2026-05-01', 'YYYY-MM-DD') + MOD(n, 30) AS order_date,
        CASE MOD(n, 4) WHEN 0 THEN 'paid' WHEN 1 THEN 'created' WHEN 2 THEN 'shipped' ELSE 'closed' END AS status,
        TO_DATE('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400 AS created_at,
        TO_DATE('2026-05-01 00:10:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400 AS updated_at
    FROM (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 10000)
) s;
COMMIT;

-- =========================================================
-- validation
-- =========================================================

SELECT 'oracle.consilens_performance_demo_table' AS check_name,
    COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(record_id) AS min_record_id, MAX(record_id) AS max_record_id,
    ROUND(SUM(amount), 4) AS amount_sum
FROM consilens_performance_demo_table;

SELECT 'oracle.users' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    MIN(id) AS min_id, MAX(id) AS max_id FROM users;

SELECT 'oracle.orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(amount), 4) AS amount_sum FROM orders;

SELECT 'oracle.fact_orders' AS check_name, COUNT(*) AS actual_rows, 10000 AS expected_rows,
    ROUND(SUM(total_amount), 4) AS total_amount_sum FROM fact_orders;
