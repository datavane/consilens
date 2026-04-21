# Consilens

> 跨数据源数据一致性校验工具。

Consilens 用于验证数据同步、迁移、ETL、回填后的结果是否一致，并输出行级、列级差异。

适合这类场景：

- MySQL → PostgreSQL / ClickHouse / StarRocks 迁移验收
- 数据同步链路校验
- ETL / 回填结果核对
- 双写 / 灰度切换对账

## 核心特性

- 跨数据源比对，不要求两侧数据集在同一个实例
- 基于 connector/dataset 抽象，架构可扩展至 ES、MongoDB、HDFS 等非关系型数据源
- 大表优先走 checksum 收敛，差异可定位到主键和字段
- 支持多格式 sink 输出：控制台、JSON 文件、CSV 文件、结果表
- 基于 SPI 的连接器插件扩展

## 内置连接器

| 数据库 | 连接器模块 | 验证状态 |
| --- | --- | --- |
| MySQL | `consilens-connector-mysql` | 已验证 |
| PostgreSQL | `consilens-connector-postgresql` | 已验证 |
| SQL Server | `consilens-connector-sqlserver` | 内置（待验证） |
| Oracle | `consilens-connector-oracle` | 内置（待验证） |
| ClickHouse | `consilens-connector-clickhouse` | 内置（待验证） |
| Doris | `consilens-connector-doris` | 内置（待验证） |
| StarRocks | `consilens-connector-starrocks` | 已验证 |
| Presto | `consilens-connector-presto` | 内置（待验证） |
| Trino | `consilens-connector-trino` | 内置（待验证） |
| TiDB | `consilens-connector-tidb` | 内置（待验证） |

说明：`内置（待验证）` 表示仓库里已有对应连接器模块，但 README 目前只把已完成端到端验证的数据库作为对外首推能力。

## 工作方式

```text
边界探测
  ↓
首轮分段
  ↓
checksum 比对
  ↓
一致段跳过
  ↓
差异段继续收敛
  ↓
小段本地精确比较
```

## 快速开始

### 构建

```bash
mvn clean package -DskipTests -Prelease
```

### 执行

```bash
./bin/consilens-cli.sh diff -c config.yaml
```

### 最小配置示例

```yaml
source:
  type: mysql
  connection:
    url: jdbc:mysql://localhost:3306/source_db
    username: ${env.MYSQL_USER}
    password: ${env.MYSQL_PASSWORD}
  resource:
    type: table
    name: orders

target:
  type: postgresql
  connection:
    url: jdbc:postgresql://localhost:5432/target_db?currentSchema=public
    username: ${env.PG_USER}
    password: ${env.PG_PASSWORD}
  resource:
    type: table
    name: orders

comparison:
  keys:
    source:
      - order_id
    target:
      - order_id
  fields:
    source:
      - order_id
      - customer_id
      - amount
      - status
      - created_at
    target:
      - order_id
      - customer_id
      - amount
      - status
      - created_at

strategy:
  mode: checksum
  algorithm: xor
  bisectionFactor: 8
  bisectionThreshold: 5000
  batchSize: 1000
  enableProfiling: false

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

请将上面的用户名和密码替换成真实值。配置支持环境变量占位符，语法为 `${env.VAR_NAME}` 或 `${env.VAR_NAME:默认值}`。

## 策略

| 策略 | 状态 | 说明 |
| --- | --- | --- |
| `checksum` | 已实现 | 推荐默认使用，支持跨数据源 |
| `join` | 已实现 | 仅支持同一个 JDBC URL |

## 文档

- [快速开始](./docs/01-快速开始.md)
- [配置详解](./docs/02-配置详解.md)
- [连接器与数据源支持](./docs/03-插件与数据库支持.md)
- [架构设计](./docs/04-架构设计.md)
- [开发指南](./docs/05-开发指南.md)

## 社交媒体

- 微信公众号，扫描二维码关注

![微信二维码](docs/img/wechat-qrcode.png)

## 联系作者

- 添加时备注：Consilens

![wechat-author-qrcode](docs/img/wechat-author-qrcode.png)