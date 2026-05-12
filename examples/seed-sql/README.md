# Consilens Demo Data Scripts

This directory contains repeatable seed scripts for the current Consilens example configurations.

## Files

- `load-mysql-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table`, `mydb.users`, `mydb.orders`, `mydb.orders_backup`, and `production.fact_orders`.
  - Creates `diff_results` for table sink examples.
  - Inserts 10000 rows into every source table.
- `load-postgresql-consilens-demo-data.sql`
  - Creates `public.consilens_performance_demo_table`, `public.daily_order_summary`, and `public.users`.
  - Inserts 10000 rows into `consilens_performance_demo_table` and `users`.
  - Builds `daily_order_summary` from the same aggregate logic used by `examples/detail-to-aggregate-custom-sql.yaml`.
- `load-doris-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` for `examples/mysql-to-doris-partitioned-checksum.yaml`.
  - Inserts 10000 rows with the partition and comparison columns used by the Doris example.
- `load-starrocks-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` and `analytics.fact_orders`.
  - Inserts 10000 rows into every StarRocks target table.

## Covered Examples

- `examples/minimal-mysql-to-pg.yaml`
- `examples/same-db-mysql-comparison.yaml`
- `examples/performance-test-mysql-vs-postgres.yaml`
- `examples/performance-test-mysql-vs-postgres-exclude.yaml`
- `examples/performance-test-mysql-vs-postgres-output-postgres.yaml`
- `examples/performance-test-mysql-vs-postgres.json`
- `examples/custom-sql-mysql-vs-postgres-checksum.yaml`
- `examples/detail-to-aggregate-custom-sql.yaml`
- `examples/mysql-to-doris-partitioned-checksum.yaml`
- `examples/performance-test-mysql-vs-starrocks.yaml`
- `examples/large-table-mysql-to-starrocks.yaml`

The scripts also include `dt`, `deleted`, and result database setup needed by the partition/filter and sink scenarios already present in the examples. The row count is intentionally 10000 for repeatable local smoke coverage; the performance example comments still describe larger production-scale workloads.

## Run

MySQL:

```bash
mysql -h localhost -P 3306 -u "$MYSQL_USER" -p < examples/seed-sql/load-mysql-consilens-demo-data.sql
```

PostgreSQL for examples that point to the `postgres` database:

```bash
psql "postgresql://$PG_USER:$PG_PASSWORD@localhost:5432/postgres" \
  -f examples/seed-sql/load-postgresql-consilens-demo-data.sql
```

PostgreSQL for `examples/minimal-mysql-to-pg.yaml`, which points to `mydb`:

```bash
createdb -h localhost -U "$PG_USER" mydb
psql "postgresql://$PG_USER:$PG_PASSWORD@localhost:5432/mydb" \
  -f examples/seed-sql/load-postgresql-consilens-demo-data.sql
```

StarRocks:

```bash
mysql -h localhost -P 9030 -u "$STARROCKS_USER" -p < examples/seed-sql/load-starrocks-consilens-demo-data.sql
```

If StarRocks uses more than one replica by default, change `"replication_num" = "1"` in the script before running it.

Doris:

```bash
mysql -h localhost -P 9030 -u "$DORIS_USER" -p < examples/seed-sql/load-doris-consilens-demo-data.sql
```

## Time Zone

For zero-difference cross-database checks that include timestamp fields, keep MySQL, PostgreSQL, and StarRocks comparison sessions on the same logical timezone. The seed scripts use Asia/Shanghai values. If the database servers use different defaults, configure the JDBC session or Consilens normalization before comparing timestamp columns.

## Expected Baseline

The default data is a zero-difference baseline for the matching example pairs:

- MySQL `consilens_demo.consilens_performance_demo_table` vs PostgreSQL `public.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs Doris `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs StarRocks `consilens_demo.consilens_performance_demo_table` for the integer fields in `performance-test-mysql-vs-starrocks.yaml`
- MySQL `production.fact_orders` vs StarRocks `analytics.fact_orders`
- MySQL `mydb.orders` vs `mydb.orders_backup`
- MySQL `mydb.users` vs PostgreSQL `public.users`

Each script ends with verification queries. Every main table should report `actual_rows = 10000` and `expected_rows = 10000`.

## Optional Difference Cases

Run these only after loading the baseline data.

Create a value mismatch in MySQL vs PostgreSQL performance data:

```sql
UPDATE public.consilens_performance_demo_table
SET amount = amount + 1
WHERE record_id = 'REC0000001000';
```

Create a target-only row in PostgreSQL:

```sql
INSERT INTO public.users (id, name, email, phone, status, created_at)
VALUES (10001, 'user_10001_extra', 'user_10001_extra@example.com', '+861380010001', 'active', '2026-01-01 00:00:00');
```

Create a MySQL same-database mismatch:

```sql
UPDATE mydb.orders_backup
SET status = 'manual_diff'
WHERE order_id = 1000;
```

Create a StarRocks target mismatch:

```sql
INSERT INTO analytics.fact_orders (
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
VALUES (
  10001,
  100001,
  200001,
  1,
  9.0000,
  9.0000,
  '2026-05-01',
  'target_extra',
  '2026-05-01 00:00:00',
  '2026-05-01 00:10:00'
);
```
