# Iceberg 数据源

`iceberg` 连接器读取 Iceberg 表的元数据与 manifests，解析出 snapshot 对应的 Parquet 数据文件，再复用文件连接器完成扫描与比对。支持本地文件系统与 S3 兼容对象存储。

## 一、配置

```yaml
source:
  type: iceberg
  name: source-iceberg
  resource:
    type: iceberg
    path: /warehouse/db/users/metadata/v3.metadata.json
    options:
      snapshotId: "1234567890"   # 可选；缺省用 current snapshot
```

| 资源 | 说明 |
| --- | --- |
| `path` 指向 `.metadata.json` | 直接读取该 metadata（本地或 S3） |
| `path` 指向本地表目录 | 读取 `metadata/` 下文件名最大的 `.metadata.json` |
| `path` 指向 S3 前缀/目录 | 列出前缀下 `.metadata.json`，取文件名最大者 |
| `snapshotId` | 可选；指定 snapshot，缺省使用 `currentSnapshot()` |
| `partition.<field>` | 可选；按分区值裁剪数据文件（精确匹配） |

`resource.type` 写成 `iceberg`（与 `source.type` 同名）即可；`iceberg` 连接器不需要
`connection` 块。选项同样支持平铺或收在 `options` 块中，见
[配置详解](./02-配置详解.md) 的「资源选项」一节。

分区裁剪示例：

```yaml
resource:
  type: iceberg
  path: /warehouse/db/events/metadata/v3.metadata.json
  options:
    partition.dt: "2026-10-27"
```

- 只读取 `DataFile.partition()` 中对应字段等于给定值的数据文件。
- 当前仅支持精确匹配；`IN`、范围、复杂分区变换需在 SQL/文件侧处理。
- 分区字段不存在会明确报错。
- 裁剪发生在下载/扫描前，仍会读取 metadata、manifest list 与 manifests。

S3 凭据使用与文件连接器相同的 `s3Region`/`s3Endpoint`/`s3PathStyle`/`s3AccessKey`/`s3SecretKey` 选项，缺省走 AWS 默认凭据链。S3 列目录会产生一次 ListObjectsV2 请求。

## 二、读取流程

1. `TableMetadataParser` 解析 metadata（S3 对象按需下载到临时目录）。
2. 选择 current/指定 snapshot。
3. 读取 manifest list、data manifests 与 delete manifests（S3 manifests 按需下载），收集数据/删除文件及其序列号、分区和 equality field IDs。
4. 数据文件接受 `PARQUET`/`ORC`；position/equality delete 文件接受 `PARQUET`/`AVRO`/`ORC`，非 Parquet 文件先转成带 field ID 的 Parquet。
5. 先应用 position deletes，再按序列号和分区应用 equality deletes；数据文件在本地创建符号链接（不支持时复制），S3 对象下载；结果统一交给 `FileDatasetHandle`。
6. `DatasetHandle.close()` 删除临时目录并关闭 S3 client。

`getMetadata()` 额外暴露：

- `iceberg.snapshotId`
- `iceberg.tableLocation`

## 三、限制

- 仅支持本地文件系统与 S3 兼容对象存储；S3 目录会列出前缀下全部对象，对象极多时应直接给 metadata 路径。
- 支持 Parquet/ORC 数据文件；Avro 未实现。snapshot 内混用两种格式会明确报错。
- 支持 position deletes（delete 文件 PARQUET/AVRO/ORC；数据文件 PARQUET/ORC，非 Parquet 文件会先转成 Parquet 再过滤）：仅当删除文件序列号大于等于数据文件序列号时，按 `_file_path`/`_pos` 过滤，避免旧删除误删 re-add 的文件。
- 支持 equality deletes（delete 文件 PARQUET/AVRO/ORC，AVRO/ORC 会先转成带 field ID 的 Parquet 再匹配；数据文件 PARQUET/ORC，ORC 数据文件在应用 equality deletes 前也会转成 Parquet）：按数据序列号（严格小于删除序列号）、partition spec/分区值和 equality field IDs 匹配，使用 `IS NOT DISTINCT FROM` 保留 NULL 键语义；partition spec 演化后若新旧行分区类型形状相同（允许字段改名）仍按分区值匹配，形状不同则明确报错。删除键先装入 DuckDB 临时表（DISTINCT），每个受影响数据文件只做一次 anti-join，避免重复读取 delete 文件。键列优先按 Parquet field ID（`parquet_schema`）解析，列改名后仍能匹配；无 field ID 的文件（如 DuckDB 重写产物）回退到 snapshot schema 列名，无法解析会明确报错。
- 序列号未分配的 delete/data 文件会明确报错，避免无法判定适用性时产生假一致。
- position/equality deletes 依赖 DuckDB JDBC；发行包（`consilens-connector-all`）已内置，自行裁剪依赖时需保留 `org.duckdb:duckdb_jdbc`，否则会得到明确的依赖缺失错误。
- ORC 转换使用 Apache ORC reader；field ID 从 `iceberg.id` ORC 属性读取，缺失时回退列名。发行包通过 `consilens-connector-all` 携带 ORC 依赖。
- 分区裁剪仅支持 `partition.<field>` 精确匹配，不解析 SQL filter；未命中的分区文件不会下载。
- 不做 snapshot 时间旅行之外的通用快照；`snapshotId` 只是源参数。
- 本地表目录选择 metadata 时按文件名字典序取最大，适用于 Iceberg 默认的零填充版本号命名。

## 四、示例

```bash
consilens-cli.sh diff -c compare.yaml
```

`compare.yaml` 的 `source`/`target` 可以是 Iceberg 与 CSV/Parquet/JDBC 的任意组合；Iceberg 侧最终表现为 Parquet 文件集合。
