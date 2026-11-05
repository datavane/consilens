# DuckDB 行级比对（opt-in）

`diff --engine duckdb` 把本地文件比对下推给 DuckDB，用 `FULL OUTER JOIN` 直接产出差异行。默认仍是 Java streaming runtime；该引擎是显式选项。

## 一、命令

```bash
consilens-cli.sh diff -c compare.yaml --engine duckdb
```

`verify` 也支持同一引擎：

```bash
consilens-cli.sh verify -c compare.yaml --engine duckdb --fail-on any
```

适用场景：

- 两侧都是本地 CSV/JSONL/JSON/Parquet；
- 数据量较大，希望避免 Java 侧全量载入与排序；
- 不需要 normalization、过滤、mappings。

## 二、约束

| 约束 | 说明 |
| --- | --- |
| 数据源 | 仅 `type=file` 且本地路径；S3/Iceberg/JDBC 不支持 |
| 键 | 两侧键数量一致；必须唯一，重复键直接失败 |
| 字段 | 两侧比较字段数量一致，按位置配对 |
| 归一化 | 不支持 `normalization`；SQL 类型比较语义 |
| 过滤 | 不支持 `comparison.filters` / 增量窗口；`verify --engine duckdb` 同样受限 |
| 映射 | mappings 生成的 SQL 资源不是 `type=file`，会被拒绝 |
| 空值 | 键比较使用 `IS NOT DISTINCT FROM`；字段差异使用 `IS DISTINCT FROM` |
| 类型 | 跨格式类型差异（如 CSV DOUBLE vs Parquet DECIMAL）按 DuckDB 类型规则处理，可能比 Java normalizer 更严格 |

## 三、执行流程

1. 校验请求：文件源、无 normalization/filter、键与字段数量。
2. 对两侧分别做重复键检查。
3. 一条聚合 SQL 统计 source/target 行数、缺失数、mismatch 数。
4. 一条差异 SQL 返回缺失行与字段变化行，`LIMIT maxDifferences + 1`。
5. 将结果转换为 `DiffRow`，再交给现有 SDK facade 附加列统计、指纹与 evidence。
6. CLI/sink/lifecycle 与默认 runtime 完全一致。

## 四、限制

- 不做 normalization：字符串 trim/大小写、时间时区、定点精度等仍以默认 runtime 为准。
- 重复键不支持多重集语义；必须先保证键唯一。
- `maxDifferences` 超限直接报错，不做截断。
- 没有 `InfoTree` 的分段/查询统计；`DiffResult.metadata` 标记 `engine=duckdb`。
- 大表会由 DuckDB 做 join/sort，内存与临时目录由 DuckDB 管理。
- 当前用于 `diff` 与 `verify`；`metrics` 的 `--engine duckdb` 是独立的聚合下推路径。
- CSV 文本列按 Java 推断结果强制 `VARCHAR`，保留 `001` 这类前导零编码；两侧键或比较字段类型不一致（如源 `VARCHAR`、目标 `BIGINT`，或 `BIGINT` 对 `DOUBLE`）时 `diff` 会直接报错并建议改用 Java 引擎，避免隐式转换造成假一致。
- 仅表头/全 NULL/空文件的列推断为 `UNKNOWN`（JSONL/CSV 空文件需配置 `columns`），DuckDB 的类型一致性检查会跳过这类列；空表与非空表可以正常比较（空 CSV 会使用 `auto_detect=false`）。
- 显式 `resource.columns`（重命名列）会转换为 DuckDB 的 `columns={...}`；无表头 CSV 必须配置 `columns`，否则明确报错。`nullValue`/`skipRows`/`delimiter` 与 Java 引擎一致。
- 显式 `types` 为 DATE/TIMESTAMP/JSON/BLOB 等非数值类型时，两个引擎都按文本比较（Java 扫描器不转换这些类型），DuckDB 会强制 `VARCHAR`。
- Parquet 的 `columns`/`types` 覆盖在 DuckDB 引擎不支持（`read_parquet` 无该参数），配置后会明确报错并建议 Java 引擎。
- CSV `charset` 支持 UTF-8、GBK/GB2312（经 gb18030）、Latin-1、UTF-16；其他字符集在 DuckDB 引擎下明确报错。显式 `columns` 不允许重复。
- JSONL 的列类型来自同一套 Java 推断；嵌套对象/数组按紧凑 JSON 文本比较（与 Java 扫描器一致），键顺序不同会判为差异，而不是按 STRUCT 结构相等处理。
- `read_json_auto` 不支持 `skipRows`/`nullValue`，JSONL 配置了这两项时 DuckDB 引擎会明确报错并建议改用 Java 引擎（Java 引擎两者都支持）。
- `metrics` 的数值聚合与 Java 引擎对齐：`avg` 保留 6 位（HALF_UP），浮点 `sum` 归一为 15 位有效数字，避免 `0.30000000000000004` 这类二进制尾差；空表或全 NULL 数值列返回 NULL 聚合，不报 binder error。

## 五、示例

`examples/files/csv-to-csv.yaml`：

```bash
consilens-cli.sh diff -c examples/files/csv-to-csv.yaml --engine duckdb
```

输出与默认引擎的差异统计一致；列统计、指纹与 evidence 由 SDK 附加。
