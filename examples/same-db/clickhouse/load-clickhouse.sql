-- orders / orders_backup seed (ClickHouse)
DROP TABLE IF EXISTS mydb.orders_backup;
DROP TABLE IF EXISTS mydb.orders;
CREATE TABLE mydb.orders (
  order_id UInt32,
  customer_id UInt32,
  amount Decimal(18,4),
  status String,
  created_at DateTime
) ENGINE = MergeTree() ORDER BY order_id;
INSERT INTO mydb.orders
SELECT toUInt32(number), toUInt32(100000 + modulo(number,500)),
  toDecimal64(10 + modulo(number,5000)/10, 4),
  multiIf(modulo(number,4)=0,'active',modulo(number,4)=1,'inactive',modulo(number,4)=2,'pending','blocked'),
  toDateTime('2026-05-01 00:00:00') + modulo(number,10000)
FROM numbers(1, 10000);
CREATE TABLE mydb.orders_backup AS mydb.orders;
INSERT INTO mydb.orders_backup SELECT * FROM mydb.orders;
ALTER TABLE mydb.orders_backup UPDATE amount = 99999.9999 WHERE order_id = 1 SETTINGS mutations_sync = 1;
INSERT INTO mydb.orders VALUES (10001, 200001, 8888.0000, 'active', '2026-05-01 12:00:00');
