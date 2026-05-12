-- Doris seed data for Consilens examples.
-- Target row count: 10000 rows for examples/mysql-to-doris-partitioned-checksum.yaml
-- Run with: mysql -h localhost -P 9030 -u "$DORIS_USER" -p < examples/seed-sql/load-doris-consilens-demo-data.sql

CREATE DATABASE IF NOT EXISTS consilens_demo;

USE consilens_demo;

DROP TABLE IF EXISTS consilens_digits;
DROP TABLE IF EXISTS consilens_seq;

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
  col_int INT,
  col_decimal DECIMAL(18,4),
  amount DECIMAL(18,4),
  status VARCHAR(20),
  updated_at DATETIME,
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
  col_int,
  col_decimal,
  amount,
  status,
  updated_at,
  dt
)
SELECT
  CONCAT('REC', LPAD(CAST(n AS STRING), 10, '0')) AS record_id,
  n * 10 AS col_int,
  CAST(ROUND(MOD(n, 100000) / 100 + 0.1234, 4) AS DECIMAL(18,4)) AS col_decimal,
  CAST(ROUND(10 + MOD(n, 5000) / 10, 4) AS DECIMAL(18,4)) AS amount,
  CASE MOD(n, 4)
    WHEN 0 THEN 'active'
    WHEN 1 THEN 'inactive'
    WHEN 2 THEN 'pending'
    ELSE 'blocked'
  END AS status,
  DATE_ADD(CAST('2026-05-01 00:05:00' AS DATETIME), INTERVAL MOD(n, 10000) SECOND) AS updated_at,
  CAST('2026-05-01' AS DATE) AS dt
FROM consilens_seq;

SELECT 'doris.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(record_id) AS min_record_id,
       MAX(record_id) AS max_record_id,
       ROUND(SUM(amount), 4) AS amount_sum
FROM consilens_demo.consilens_performance_demo_table;
