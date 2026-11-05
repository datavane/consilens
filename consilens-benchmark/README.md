# consilens-benchmark

> 主链路基准已升级为可重复采样：e2e 默认预热 1 次、测量 5 次，以 p95 延迟作为 baseline 主指标，并保存每次原始样本。`FAILED` 会返回非零退出码。

## 主链路场景

| 场景 | 策略 | 说明 |
| --- | --- | --- |
| `G01` / `G02` | checksum xor | MySQL / PostgreSQL 生成数据 |
| `C-CONCAT` | checksum concat | 对照 concat 摘要算法 |
| `C-ROW-HASH` | checksum xor + row-hash | 终端段 row-hash 回查 |
| `C-FULL` | checksum xor + full | 终端段全字段拉取 |
| `C-COMPOSITE` | checksum xor + row-hash | `[key_group,key_part]` 复合键 |
| `J-WHOLE` | join | 同库整表 join |
| `J-SEGMENTED` | - | 当前 core 未实现，报告为 `UNSUPPORTED` |

正式运行示例：

```bash
bin/run-benchmark.sh e2e \
  --scenario G01,C-CONCAT,C-ROW-HASH,C-FULL,J-WHOLE \
  --dataset-id 100m-sparse-diff5 \
  --key-distribution sparse \
  --difference-type mismatch \
  --expected-differences G01=5000000,C-CONCAT=5000000,C-ROW-HASH=5000000,C-FULL=5000000,J-WHOLE=5000000 \
  --e2e-warmup-runs 2 \
  --e2e-measurement-runs 7
```

`--expected-differences` 必须填写数据生成报告中的实际总差异数，不按比例估算。不同数据量、差异率、键分布必须使用不同 `--dataset-id`，否则 baseline 无法区分矩阵点。

结果包含 `durationP50Ms`、`durationP95Ms`、`firstDifferenceObservedMsP50`、`instrumentedQueryCountP50`、`segmentCountP50`、`logicalRowsScannedP50`、`resultBytesFetchedEstimateP50`、`heapPeakBytesP95` 和准确性。数据库 CPU/IO、物理扫描行数、真实网络字节、精确 SQL 数在未配置厂商级探针时显示 N/A；`instrumentedQueryCount` 仅是已埋点查询下界。

外部场景可通过 `--scenario CUSTOM --scenario-config CUSTOM=/absolute/path/custom.yaml --expected-differences CUSTOM=COUNT` 接入，仍使用相同的预热、采样和准确性校验。

`consilens-benchmark` 是 Consilens 的性能基准测试套件，覆盖算法微基准
（JMH）与端到端比对基准（复用 `examples/` 与 CLI 子进程），并产出基线
JSON 与漂移报告。

设计详见 `logs/06-benchmark工程设计.md`。
员工使用指南（数据量 × 差异量矩阵测试、参数与结果解读）见
`docs/07-benchmark性能基准测试使用指南.md`。

## 编译与单元测试

```bash
./mvnw -pl consilens-benchmark -am compile
./mvnw -pl consilens-benchmark -am test
```

`mvn test` 仅跑 `*Test` 单元测试，不触发 JMH，不拉子进程。

## 生成千万/亿级测试数据

micro 使用内存 fixture；大数据量请使用数据库批量生成器。它按 batch 写入两张表，
不会把全量数据放进 JVM，并生成实际行数、mismatch、源端缺失、目标端缺失校验报告。

```bash
bin/generate-benchmark-data.sh \
  --jdbc-url 'jdbc:mysql://127.0.0.1:13306/consilens_benchmark' \
  --username "$MYSQL_USER" --password "$MYSQL_PASSWORD" \
  --rows 100000000 --diff-ratio 0.05 --batch-size 5000 --seed 42
```

常用参数：`--source-missing-ratio`、`--target-missing-ratio`、`--resume`、
`--report`、`--source-table`、`--target-table`。完整的数据规则、千万/亿级执行步骤和
校验报告说明见 `docs/07-benchmark性能基准测试使用指南.md` 的第八节。

生成完成后，可设置 `BENCHMARK_JDBC_URL`、`BENCHMARK_DB_USER`、
`BENCHMARK_DB_PASSWORD`，用 `bin/run-benchmark.sh e2e --scenario G01` 直接对
`benchmark_source` 与 `benchmark_target` 做真实数据库比对；表名可通过
`BENCHMARK_SOURCE_TABLE`、`BENCHMARK_TARGET_TABLE` 覆盖。
PostgreSQL 数据使用 `bin/run-benchmark.sh e2e --scenario G02`。

## 直接执行基准

```bash
./mvnw -pl consilens-benchmark -am package
./mvnw -pl consilens-benchmark -Pbenchmark exec:java \
  -Dexec.args="--mode micro"
```

## 运行方式

推荐通过仓库根目录的 `bin/run-benchmark.sh` 执行，脚本会先构建模块与依赖，
再以 `benchmark` profile 启动 `com.consilens.benchmark.runner.Main`。

```bash
# 微基准（不依赖数据库）
bin/run-benchmark.sh micro

# 端到端基准（需要数据库凭据，无库环境自动 SKIPPED 且退出 0）
bin/run-benchmark.sh e2e

# 微基准 + 端到端
bin/run-benchmark.sh all
```

可选参数透传给 Main：`--scenario E02,E03`、`--update-baseline`、`--forks N`、
`--warmup-iterations N`、`--measurement-iterations N`。

### 前置条件

- JDK 11+，Maven Wrapper 可用。
- e2e 模式需要 CLI fat jar 已构建：`./mvnw -pl consilens-cli -am package`。
- e2e 模式需要数据库凭据环境变量（复用 `examples/README.md` 的约定）：
  E02/E03 需要 `MYSQL_USER`、`MYSQL_PASSWORD`、`PG_USER`、`PG_PASSWORD`；
  join 场景需要 `MYSQL_USER`、`MYSQL_PASSWORD`。凭据缺失时场景标记
  `SKIPPED`，不会判失败。
- 可通过 `CONSILENS_CLI_JAR` 覆盖 CLI jar 路径，`CONSILENS_EXAMPLES_DIR`
  覆盖 examples 目录，`CONSILENS_BENCHMARK_SKIP_BUILD=1` 跳过自动构建。

### 基线更新

首次运行或需要重置基线时：

```bash
bin/run-benchmark.sh all --update-baseline
```

基线写入 `src/main/resources/baseline/benchmark-baseline.json`，人工 review
后提交。常规运行只做漂移判定（`--update-baseline` 缺省不写基线），有回归时
退出码非零。

报告与结果 JSON 输出到 `target/benchmark/report/`。
