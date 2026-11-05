# Consilens examples 测试体系

## 目录结构

```text
examples/
├── cross-db/          # 跨库矩阵：10 个 MySQL -> 目标端 pair
│   ├── mysql-pg/      #   每个 pair：load-mysql.sql + load-<target>.sql + 01..11 场景
│   ├── mysql-oracle/
│   └── ...            # clickhouse/tidb/starrocks/doris/trino/presto/oceanbase/sqlserver
├── same-db/           # 同库矩阵：11 种数据源同实例自比
│   ├── mysql/         #   每个数据源：load-<db>.sql + 01-identical + 02-join-diff
│   ├── postgresql/
│   └── ...            # oracle/sqlserver/clickhouse/tidb/starrocks/doris/oceanbase/trino/presto
├── run-comparison-test.sh
├── test-baselines.json
└── README.md
```

每个 `cross-db/mysql-*` pair 场景编号固定：

| 编号 | 场景 | 基线 |
| --- | --- | --- |
| 01 | 自定义 SQL + checksum | 精确 total=4 |
| 02 | 明细聚合 vs 预聚合表 | totalMin |
| 03 | 大表并发 + table sink | 精确 total=4 |
| 04 | 字段映射 column/expression/literal | 精确 |
| 05 | 同库 join + normalization | 精确 |
| 06 | 性能全字段（200 万行，默认跳过） | totalMin |
| 07 | 字段排除 exclude | 精确 |
| 08 | 输出方言（仅 mysql-pg） | totalMin |
| 09 | JSON 配置格式 | 精确 |
| 10 | 分区过滤（仅 mysql-doris） | 精确 |
| 11 | 完全一致对照 | total=0 |

## 运行回归

1. 构建 CLI：

   ```bash
   ./mvnw -pl consilens-cli -am package
   ```

2. 注入所有数据源凭据（脚本对缺失变量 fail-fast）：

   ```bash
   export MYSQL_USER=... MYSQL_PASSWORD=...
   export PG_USER=... PG_PASSWORD=...
   export CLICKHOUSE_USER=... CLICKHOUSE_PASSWORD=...
   export TIDB_USER=... TIDB_PASSWORD=...
   export STARROCKS_USER=... STARROCKS_PASSWORD=...
   export DORIS_USER=... DORIS_PASSWORD=...
   export ORACLE_USER=... ORACLE_PASSWORD=...
   export SQLSERVER_USER=... SQLSERVER_PASSWORD=...
   export TRINO_USER=... TRINO_PASSWORD=...
   export PRESTO_USER=... PRESTO_PASSWORD=...
   export OCEANBASE_USER=... OCEANBASE_PASSWORD=...
   ```

3. 执行：

   ```bash
   cd examples
   ./run-comparison-test.sh all        # 跨库 + 同库全量
   ./run-comparison-test.sh cross-db   # 仅跨库矩阵
   ./run-comparison-test.sh same-db    # 仅同库矩阵
   ./run-comparison-test.sh mysql-pg   # 单个 pair
   ```

可选开关：

```bash
CONSILENS_RUN_PERFORMANCE=1 ./run-comparison-test.sh all   # 含 200 万行性能用例
CONSILENS_CHECK_BASELINE=0 ./run-comparison-test.sh all    # 跳过基线断言
```

目标端种子加载是 best-effort：客户端未安装时（如 sqlplus）会提示手动加载，其余照常比对。

## 无数据库环境下的校验

每次 `./mvnw test` 都会执行 `ExampleCoverageMatrixTest`（123 个用例）：

- 所有配置可加载且通过 `config.validate()`；
- 配置字段、枚举、sink format/type、normalization 参数、JSON 值类型全覆盖；
- 每个 pair/数据源场景文件齐全；
- 种子刻意差异标记齐全；
- `test-baselines.json` 与 examples 文件一一对应。

真实比对由 `run-comparison-test.sh` 在发版前执行。

## 新增数据源规范（四件套）

新增数据源必须同时落地四项，缺一即视为未完成：

1. `cross-db/mysql-<name>/`：`load-mysql.sql` + `load-<name>.sql` + 01-11 场景；
2. `same-db/<name>/`：`load-<name>.sql` + `01-identical.yaml` + `02-join-diff.yaml`；
3. `test-baselines.json`：登记新 pair 的 01-11 与 same-db 01/02 基线（01 精确、02 精确、06 totalMin）；
4. 测试同步：`ExampleCoverageMatrixTest.EXPECTED_PAIRS` / `EXPECTED_SAME_DB`、`CrossDatabaseComparisonIntegrationTest` 场景清单、`run-comparison-test.sh` 的 pair 数组与加载分支。

## 差异语义

- `SOURCE_MISSING`：目标端多出（种子在目标端额外插入，如 `REC_EXTRA_001`）；
- `TARGET_MISSING`：目标端缺失（种子在目标端跳过，如 `REC0000000005`）；
- `MISMATCH`：同主键两侧值不同（种子在目标端改值，如 amount/status）。

`11-identical.yaml` 与差异用例互为反向钳制：一致用例断言 total=0，差异用例断言精确计数。
