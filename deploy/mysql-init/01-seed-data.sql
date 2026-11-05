-- -------------------------------------------------------------------
-- init.sql — Seed test data for consilens diff demo
--
-- Creates two databases (source_db, target_db) each with an 'orders'
-- table. source has 100 rows; target has 99 rows (one missing) plus
-- a deliberately different amount so the diff task finds 2 issues.
--
-- Mounted to /docker-entrypoint-initdb.d by docker-compose so it runs
-- automatically on first container startup.
-- -------------------------------------------------------------------

-- =========================================
-- SOURCE database
-- =========================================
CREATE DATABASE IF NOT EXISTS source_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE source_db;

CREATE TABLE IF NOT EXISTS orders (
    id          INT             NOT NULL AUTO_INCREMENT,
    customer    VARCHAR(64)     NOT NULL,
    amount      DECIMAL(10, 2)  NOT NULL DEFAULT 0.00,
    status      VARCHAR(16)     NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Insert 100 sample orders
INSERT INTO orders (id, customer, amount, status, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
SELECT
    n,
    CONCAT('customer_', LPAD(n, 3, '0')),
    ROUND(RAND(n) * 1000, 2),
    ELT(1 + MOD(n, 3), 'pending', 'shipped', 'delivered'),
    TIMESTAMP('2025-01-01 00:00:00') + INTERVAL n HOUR
FROM seq;

-- =========================================
-- TARGET database
-- =========================================
CREATE DATABASE IF NOT EXISTS target_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE target_db;

CREATE TABLE IF NOT EXISTS orders (
    id          INT             NOT NULL AUTO_INCREMENT,
    customer    VARCHAR(64)     NOT NULL,
    amount      DECIMAL(10, 2)  NOT NULL DEFAULT 0.00,
    status      VARCHAR(16)     NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Copy 99 rows (skip id=50) and mutate amount on id=10
INSERT INTO target_db.orders (id, customer, amount, status, created_at)
SELECT
    id,
    customer,
    CASE
        WHEN id = 10 THEN amount + 100.00   -- deliberately wrong amount
        ELSE amount
    END,
    status,
    created_at
FROM source_db.orders
WHERE id <> 50;
