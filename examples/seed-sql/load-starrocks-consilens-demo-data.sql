-- StarRocks seed data for Consilens examples.
-- Target row count: 10000 rows in each StarRocks target table used by the examples.
-- Run with: mysql -h localhost -P 9030 -u "$STARROCKS_USER" -p < examples/seed-sql/load-starrocks-consilens-demo-data.sql

CREATE DATABASE IF NOT EXISTS consilens_demo;
CREATE DATABASE IF NOT EXISTS analytics;

USE consilens_demo;

DROP TABLE IF EXISTS consilens_seq;
DROP TABLE IF EXISTS consilens_digits;

CREATE TABLE consilens_digits (
  d INT NOT NULL
)
ENGINE=OLAP
DUPLICATE KEY(d)
DISTRIBUTED BY HASH(d) BUCKETS 1
PROPERTIES ("replication_num" = "1");

INSERT INTO consilens_digits VALUES
  (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

CREATE TABLE consilens_seq (
  n INT NOT NULL
)
ENGINE=OLAP
DUPLICATE KEY(n)
DISTRIBUTED BY HASH(n) BUCKETS 10
PROPERTIES ("replication_num" = "1");

INSERT INTO consilens_seq
SELECT ones.d + tens.d * 10 + hundreds.d * 100 + thousands.d * 1000 + 1 AS n
FROM consilens_digits ones
CROSS JOIN consilens_digits tens
CROSS JOIN consilens_digits hundreds
CROSS JOIN consilens_digits thousands
WHERE ones.d + tens.d * 10 + hundreds.d * 100 + thousands.d * 1000 < 10000;

DROP TABLE IF EXISTS consilens_performance_demo_table;

CREATE TABLE consilens_performance_demo_table (
  record_id VARCHAR(16) NOT NULL,
  col_tinyint TINYINT,
  col_smallint SMALLINT,
  col_mediumint INT,
  col_int INT,
  col_bigint BIGINT,
  col_unsigned_int BIGINT,
  col_float FLOAT,
  col_double DOUBLE,
  col_decimal DECIMAL(18,4),
  col_numeric DECIMAL(18,4),
  col_char CHAR(10),
  col_varchar_50 VARCHAR(50),
  col_varchar_100 VARCHAR(100),
  col_varchar_255 VARCHAR(255),
  col_text STRING,
  col_mediumtext STRING,
  col_binary STRING,
  col_varbinary STRING,
  col_blob STRING,
  col_date DATE,
  col_datetime DATETIME,
  col_timestamp DATETIME,
  col_time VARCHAR(8),
  col_boolean BOOLEAN,
  col_tinyint_bool TINYINT,
  col_enum VARCHAR(20),
  col_set VARCHAR(20),
  col_json JSON,
  user_name VARCHAR(64),
  email VARCHAR(128),
  phone VARCHAR(32),
  address VARCHAR(255),
  city VARCHAR(64),
  country VARCHAR(64),
  postal_code VARCHAR(20),
  amount DECIMAL(18,4),
  balance DECIMAL(18,4),
  credit_limit DECIMAL(18,4),
  status VARCHAR(20),
  category VARCHAR(30),
  priority SMALLINT,
  score DOUBLE,
  created_at DATETIME,
  updated_at DATETIME,
  deleted TINYINT NOT NULL,
  dt DATE NOT NULL
)
ENGINE=OLAP
DUPLICATE KEY(record_id)
PARTITION BY RANGE(dt) (
  PARTITION p20260501 VALUES [('2026-05-01'), ('2026-05-02')),
  PARTITION pmax VALUES [('2026-05-02'), ('2030-01-01'))
)
DISTRIBUTED BY HASH(record_id) BUCKETS 10
PROPERTIES ("replication_num" = "1");

INSERT INTO consilens_performance_demo_table (
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
  CONCAT('REC', LPAD(CAST(n AS STRING), 10, '0')) AS record_id,
  MOD(n, 100) AS col_tinyint,
  MOD(n, 30000) AS col_smallint,
  n * 3 AS col_mediumint,
  n * 10 AS col_int,
  n * 1000003 AS col_bigint,
  2147483648 + n AS col_unsigned_int,
  CAST(MOD(n, 1000) + 0.125 AS FLOAT) AS col_float,
  CAST(MOD(n, 100000) + 0.25 AS DOUBLE) AS col_double,
  CAST(ROUND(MOD(n, 100000) / 100 + 0.1234, 4) AS DECIMAL(18,4)) AS col_decimal,
  CAST(ROUND(MOD(n, 100000) / 50 + 0.5678, 4) AS DECIMAL(18,4)) AS col_numeric,
  CONCAT('C', LPAD(CAST(n AS STRING), 9, '0')) AS col_char,
  CONCAT('v50_', LPAD(CAST(n AS STRING), 5, '0')) AS col_varchar_50,
  CONCAT('v100_', LPAD(CAST(n AS STRING), 5, '0'), '_stable') AS col_varchar_100,
  CONCAT('v255_', LPAD(CAST(n AS STRING), 5, '0'), '_stable_payload') AS col_varchar_255,
  CONCAT('text-', LPAD(CAST(n AS STRING), 5, '0')) AS col_text,
  CONCAT('mediumtext-', LPAD(CAST(n AS STRING), 5, '0'), '-', REPEAT('x', MOD(n, 32))) AS col_mediumtext,
  LPAD(HEX(n), 16, '0') AS col_binary,
  LPAD(HEX(n * 17), 32, '0') AS col_varbinary,
  LPAD(HEX(n * 31), 32, '0') AS col_blob,
  CAST('2026-05-01' AS DATE) AS col_date,
  CAST('2026-05-01 00:00:00' AS DATETIME) AS col_datetime,
  CAST('2026-05-01 00:00:00' AS DATETIME) AS col_timestamp,
  '00:00:00' AS col_time,
  MOD(n, 2) = 0 AS col_boolean,
  MOD(n, 2) AS col_tinyint_bool,
  CASE MOD(n, 4)
    WHEN 0 THEN 'new'
    WHEN 1 THEN 'processing'
    WHEN 2 THEN 'done'
    ELSE 'failed'
  END AS col_enum,
  CASE MOD(n, 3)
    WHEN 0 THEN 'a,b'
    WHEN 1 THEN 'b'
    ELSE 'c'
  END AS col_set,
  PARSE_JSON(CONCAT('"json_', LPAD(CAST(n AS STRING), 5, '0'), '"')) AS col_json,
  CONCAT('user_', LPAD(CAST(n AS STRING), 5, '0')) AS user_name,
  CONCAT('user_', LPAD(CAST(n AS STRING), 5, '0'), '@example.com') AS email,
  CONCAT('+861380', LPAD(CAST(n AS STRING), 6, '0')) AS phone,
  CONCAT('No.', CAST(n AS STRING), ' Consilens Road') AS address,
  CASE MOD(n, 4)
    WHEN 0 THEN 'Shanghai'
    WHEN 1 THEN 'Beijing'
    WHEN 2 THEN 'Shenzhen'
    ELSE 'Hangzhou'
  END AS city,
  'CN' AS country,
  LPAD(CAST(MOD(n, 1000000) AS STRING), 6, '0') AS postal_code,
  CAST(ROUND(10 + MOD(n, 5000) / 10, 4) AS DECIMAL(18,4)) AS amount,
  CAST(ROUND(1000 + MOD(n, 8000) / 10, 4) AS DECIMAL(18,4)) AS balance,
  CAST(ROUND(5000 + MOD(n, 3000) / 10, 4) AS DECIMAL(18,4)) AS credit_limit,
  CASE MOD(n, 4)
    WHEN 0 THEN 'active'
    WHEN 1 THEN 'inactive'
    WHEN 2 THEN 'pending'
    ELSE 'blocked'
  END AS status,
  CASE MOD(n, 5)
    WHEN 0 THEN 'retail'
    WHEN 1 THEN 'finance'
    WHEN 2 THEN 'logistics'
    WHEN 3 THEN 'manufacturing'
    ELSE 'public'
  END AS category,
  MOD(n, 5) + 1 AS priority,
  CAST(MOD(n, 100) + 0.5 AS DOUBLE) AS score,
  CAST('2026-05-01 00:00:00' AS DATETIME) AS created_at,
  CAST('2026-05-01 00:05:00' AS DATETIME) AS updated_at,
  IF(MOD(n, 10) = 0, 1, 0) AS deleted,
  CAST('2026-05-01' AS DATE) AS dt
FROM consilens_seq;

USE analytics;

DROP TABLE IF EXISTS fact_orders;

CREATE TABLE fact_orders (
  order_id BIGINT NOT NULL,
  customer_id INT NOT NULL,
  product_id INT NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL,
  total_amount DECIMAL(18,4) NOT NULL,
  order_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
)
ENGINE=OLAP
DUPLICATE KEY(order_id)
PARTITION BY RANGE(order_date) (
  PARTITION p202605 VALUES [('2026-05-01'), ('2026-06-01')),
  PARTITION pmax VALUES [('2026-06-01'), ('2030-01-01'))
)
DISTRIBUTED BY HASH(order_id) BUCKETS 10
PROPERTIES ("replication_num" = "1");

INSERT INTO fact_orders (
  order_id,
  customer_id,
  product_id,
  quantity,
  unit_price,
  total_amount,
  order_date,
  status,
  created_at,
  updated_at
)
SELECT
  order_id,
  customer_id,
  product_id,
  quantity,
  unit_price,
  CAST(quantity * unit_price AS DECIMAL(18,4)) AS total_amount,
  order_date,
  status,
  created_at,
  updated_at
FROM (
  SELECT
    n AS order_id,
    100000 + MOD(n, 500) AS customer_id,
    200000 + MOD(n, 1000) AS product_id,
    1 + MOD(n, 10) AS quantity,
    CAST(ROUND(5 + MOD(n, 2000) / 10, 4) AS DECIMAL(18,4)) AS unit_price,
    DATE_ADD(CAST('2026-05-01' AS DATE), INTERVAL MOD(n, 30) DAY) AS order_date,
    CASE MOD(n, 4)
      WHEN 0 THEN 'paid'
      WHEN 1 THEN 'created'
      WHEN 2 THEN 'shipped'
      ELSE 'closed'
    END AS status,
    DATE_ADD(CAST('2026-05-01 00:00:00' AS DATETIME), INTERVAL MOD(n, 10000) SECOND) AS created_at,
    DATE_ADD(CAST('2026-05-01 00:10:00' AS DATETIME), INTERVAL MOD(n, 10000) SECOND) AS updated_at
  FROM consilens_demo.consilens_seq
) s;

SELECT 'starrocks.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(record_id) AS min_record_id,
       MAX(record_id) AS max_record_id,
       ROUND(SUM(amount), 4) AS amount_sum
FROM consilens_demo.consilens_performance_demo_table;

SELECT 'starrocks.analytics.fact_orders' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       ROUND(SUM(total_amount), 4) AS total_amount_sum
FROM analytics.fact_orders;
