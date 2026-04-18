# Consilens CLI

Consilens CLI 是一个跨数据库数据一致性校验工具，通过 YAML/JSON 配置文件驱动，支持多种校验策略和输出方式。

## 功能特性

- **多数据库支持**：MySQL、PostgreSQL、Oracle、SQL Server、StarRocks、TiDB、Doris、ClickHouse、Presto/Trino 等 10 种数据库
- **双策略支持**：
  - `checksum`（默认）：基于分组校验和的递归二分对比，适合跨数据库、大数据量场景
  - `join`：基于 SQL FULL OUTER JOIN 的快速对比，适合同库或高速互联场景
- **多种 Checksum 算法**：`concat`（拼接，默认）、`xor`（异或）
- **本地比较可控**：终局小段默认使用完整行比较，必要时可显式启用主键 + 行哈希过滤
- **灵活输出（result.sinks）**：支持控制台摘要、JSON/CSV 文件以及写入数据库表，可同时配置多个 sink
- **配置文件驱动**：YAML 或 JSON 格式

## 快速开始

### 1. 构建并解包发行版

```bash
mvn clean package -DskipTests -Prelease
tar -xzf consilens-dist/target/consilens-*.tar.gz -C /opt/consilens
```

### 2. 生成配置模板

```bash
# 生成基础 YAML 模板
./bin/consilens-cli.sh config generate -o my-config.yaml

# 生成高级模板（包含输出到数据库表等完整选项）
./bin/consilens-cli.sh config generate -o advanced-config.yaml -t advanced

# 生成 JSON 格式模板
./bin/consilens-cli.sh config generate -o my-config.json -f json
```

### 3. 编辑配置文件

填入真实的数据库连接信息（详见[配置文件格式](#配置文件格式)）。

### 4. 验证配置

```bash
./bin/consilens-cli.sh config validate -c my-config.yaml
```

### 5. 执行数据对比

```bash
./bin/consilens-cli.sh diff -c my-config.yaml

# 使用详细日志
./bin/consilens-cli.sh diff -c my-config.yaml --verbose

# 仅做干运行（验证连接和行数，不执行实际对比）
./bin/consilens-cli.sh diff -c my-config.yaml --dry-run
```

## 命令参考

```
consilens <command> [options]

命令：
  config          配置管理
  diff            执行数据对比

选项：
  -h, --help      显示帮助信息
  -V, --version   显示版本信息
```

### `config generate`

```
./bin/consilens-cli.sh config generate -o <file> [选项]

选项：
  -o, --output    输出文件路径（必需）
  -f, --format    格式：yaml 或 json（默认：yaml）
  -t, --type      模板类型：basic 或 advanced（默认：basic）
```

### `config validate`

```
./bin/consilens-cli.sh config validate -c <file> [选项]

选项：
  -c, --config          配置文件路径（必需）
  --test-connection     测试数据库连接
  --verbose             显示详细验证结果
```

### `diff`

```
./bin/consilens-cli.sh diff -c <file> [选项]

选项：
  -c, --config    配置文件路径（必需）
  --dry-run       仅验证连接和行数，不执行实际对比
  --verbose       输出详细配置和进度信息
```

## 配置文件格式

### 最小配置示例

```yaml
source:
  type: mysql
  url: jdbc:mysql://localhost:3306/source_db
  username: user1
  password: password1

target:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/target_db?currentSchema=public
  username: user2
  password: password2

comparison:
  tables:
    source: orders
    target: orders
  keys:
    source:
      - order_id
    target:
      - order_id

strategy:
  mode: checksum
  algorithm: xor

result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff_results.json
        pretty: true
```

### 完整配置说明

```yaml
source:
  type: mysql
  url: jdbc:mysql://localhost:3306/database1?useSSL=false&serverTimezone=UTC
  username: user1
  password: password1

target:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/database2?currentSchema=public&ssl=false&ApplicationName=consilens
  username: user2
  password: password2

comparison:
  tables:
    source: users
    target: customers
  keys:
    source:
      - id
      - email
    target:
      - id
      - email
  fields:
    source:
      - name
      - phone
      - created_at
    target:
      - name
      - phone
      - created_at
  extraColumns:
    - updated_at
  where:
    source: "status = 'active'"
    target: "status = 'active'"

strategy:
  mode: checksum
  algorithm: xor
  bisectionFactor: 4
  bisectionThreshold: 10000
  batchSize: 1000
  enableProfiling: false
  localCompare:
    mode: full

# ─── 并发配置 ────────────────────────────────────────────
concurrency:
  io:                    # 用于数据库 I/O 的线程池
    core: 8
    max: 32
    queueSize: 10000
    keepAliveSeconds: 60
    threadNamePrefix: consilens-io-
  cpu:                   # 用于哈希计算的 CPU 线程池
    core: 4
    max: 8
    queueSize: 10000
    keepAliveSeconds: 60
    threadNamePrefix: consilens-cpu-

# ─── 结果输出（result.sinks）────────────────────────────
# 可配置多个 sink，每个 sink 独立处理输出
result:
  sinks:
    # ── 控制台摘要输出 ──
    - format: console
      type: result

    # ── 输出差异行到 JSON 文件 ──
    - format: json
      type: diff-record
      properties:
        path: ./diff_results.json   # 固定路径
        pretty: true

    # ── 输出差异行到 CSV 文件 ──
    # - format: csv
    #   type: diff-record
    #   properties:
    #     path: ./diff_results_${taskId}.csv   # 输出文件路径，支持 ${变量名} 占位符
    #     delimiter: ","                         # 列分隔符
    #     includeHeader: true                    # 是否输出表头

    # ── 输出差异行到数据库表 ──
    # - format: table
    #   type: diff-record
    #   properties:
    #     url: jdbc:mysql://localhost:3306/results?useSSL=false&serverTimezone=UTC
    #     username: consilens_user
    #     password: consilens_pass
    #     driver: com.mysql.cj.jdbc.Driver
    #     maxPoolSize: 10
    #     prefix: diff_results_       # 表名前缀
    #     suffixTimestamp: true        # 追加时间戳后缀
    #     createTable: true            # 不存在时自动建表
    #     dropIfExists: false          # 是否先删除同名表
    #     defaultColumnLength: 500     # VARCHAR 列默认长度
    #     batchSize: 1000              # 批量插入大小
```

## 策略选择指南

| 场景 | 推荐策略 |
|------|----------|
| 两库在同一实例，或网络极快 | `join` |
| 跨数据库、跨机房 | `checksum` |
| 超大表（> 千万行）跨库 | `checksum` + `algorithm: xor` |

### `checksum` 策略

通过递归二分法将表分段，每段独立计算校验和后比较，网络传输量极小。支持任意数据库组合。

推荐组合：

- 默认优先 `algorithm: xor`
- 终局小段默认使用 `localCompare.mode: full`
- 只有明确配置 `localCompare.mode: row-hash` 时，才会先拉主键 + 行哈希再回查差异行

### `join` 策略

在数据库端直接执行 FULL OUTER JOIN，结果直接返回差异行，速度最快，但要求两侧库可互访（如同实例不同 schema）。

## 数据标准化

对跨数据库比对（如 MySQL vs PostgreSQL）中常见的类型差异，Consilens 内置自动标准化逻辑，例如：

- 布尔值（`TINYINT(1)` vs `BOOLEAN`）
- 数字精度（浮点截断）
- 日期时间格式（时区对齐）
- 字符串前后空格

如需自定义，可在配置中添加 `normalization` 节，详见[开发文档](../docs/02-配置详解.md)。

## 性能调优

1. **并发池**：`concurrency.io` 控制数据库查询并发，`concurrency.cpu` 控制哈希计算并发，根据机器核数和网络延迟调整
2. **bisectionThreshold**：阈值越小分段越细，并发度越高；但分段过多时协调开销也会上升，建议 5000–20000
3. **batchSize**：单次 SQL 查询返回行数，内存较大时可适当调高（如 5000）
4. **JVM 参数**：大数据量建议加大堆内存，如 `-Xmx8g -Xms8g`

## 故障排除

**连接失败**：检查 URL、用户名、密码，以及数据库服务是否启动、防火墙是否放行

**配置验证失败**：使用 `config validate --verbose` 查看详细错误；注意两侧 `comparison.keys.source`/`comparison.keys.target` 数量必须相等，`strategy.mode` 必须是 `checksum` 或 `join`，`strategy.algorithm` 必须是 `concat` 或 `xor`

**内存不足**：调小 `batchSize` 或 `bisectionThreshold`，并加大 JVM 堆内存

**结果有差异但不确定原因**：加 `--verbose` 开启详细日志，检查两侧数据类型映射是否需要标准化配置
