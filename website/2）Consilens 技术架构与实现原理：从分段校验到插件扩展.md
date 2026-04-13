# Consilens 技术架构与实现原理：从分段校验到插件扩展

如果把 Consilens 只看成一个“跑一次 diff 的命令行工具”，很容易低估它真正复杂的部分。

跨数据库一致性校验真正难的，不在 CLI，也不在某个 hash 函数，而在三件事怎么同时成立：

1. **跨库可比**：不同数据库的数据类型、时间函数、布尔表达和 NULL 语义要被收敛成一致表示；
2. **大表可跑**：不能靠把整张表拖到客户端来解决问题；
3. **结果可消费**：最后输出的不只是“是否一致”，还要能进入文件、控制台或数据库表。

Consilens 的架构，基本就是围绕这三件事拆出来的。

## 模块怎么拆，决定了后面会不会缠在一起

当前仓库的核心模块边界大致是这样：

| 模块 | 主要职责 |
| --- | --- |
| `consilens-cli` | 解析配置、创建数据库适配器、组装执行链路、选择比对策略 |
| `consilens-core` | `ChecksumDiffer` / `JoinDiffer`、分段递归、差异模型、线程池 |
| `consilens-connector` | 数据库方言、SQL 生成、类型规范化、元数据访问 |
| `consilens-spi` | 运行时插件加载，负责发现并装配数据库方言 |
| `consilens-sink` | 输出 SPI、内置 console/json/csv/table sink、生命周期桥接 |
| `consilens-dist` | 发行包组装，把 `bin/`、`conf/`、`libs/`、`plugins/` 打成可运行产物 |
| `consilens-common` | 跨模块共享的基础模型和工具类 |

这个拆法的价值在于：**算法、方言、输出三条线彼此独立。**

换句话说：

- 你可以换数据库插件，不碰算法；
- 可以加输出格式，不动 `ChecksumDiffer`；
- 也可以改算法收敛逻辑，不需要重新设计 CLI 配置结构。

这比把所有能力都塞进一个“大型服务类”里更适合长期演进。

## 主执行链路其实很短，但每一跳都要稳

从 CLI 到最终结果，主链路并不复杂：

```text
读取配置
  ->
创建 source / target DatabaseAdapter
  ->
构造 TableSegment
  ->
按 strategy 选择 ChecksumDiffer 或 JoinDiffer
  ->
执行比较，产出 DiffResult
  ->
通过 SinkManager 路由到 console / json / csv / table
```

这里有两个容易被忽略、但实际上很关键的点。

第一，**比较对象不是直接的 JDBC 连接，而是被封装后的 `DatabaseAdapter + TableSegment`**。  
这样算法层不需要知道连接池怎么建、SQL 怎么拼、schema 怎么处理，它拿到的是一个已经准备好的“可比较表段”。

第二，**输出不是在算法里硬编码写文件**。  
算法只产出统一的差异模型和执行树，真正的输出由 sink 子系统接手。

这两个边界一旦守住，后续的扩展成本会低很多。

## `checksum` 为什么是主路径

因为它最符合大表、跨库场景的现实约束。

### Phase 1：边界探测

真正执行 checksum 之前，Consilens 不会先傻乎乎地对全表做一次哈希聚合。  
它先拿两侧表的基础边界：

```sql
SELECT COUNT(*) FROM orders;
SELECT MIN(order_id) FROM orders;
SELECT MAX(order_id) FROM orders;
```

这一步的目的不是“先看看”，而是决定后面的执行策略：

- 如果总行数已经低于阈值，直接进入本地精确比较；
- 如果规模明显偏大，就开始走分段收敛。

这么做的原因很简单：  
**全表 checksum 虽然听起来直接，但对超大表来说未必是最便宜的第一步。**

### Phase 2：首轮多分段，而不是一上来纯二分

Consilens 的第一轮切分更接近“多路分段”，不是传统意义上的纯二分。

它会根据 `bisectionFactor` 先把较大的一侧表切成多个范围段，再在另一侧创建镜像范围。  
这样做有两个收益：

1. 第一轮可以并行跑多个分段；
2. 大量一致区间可以在第一层就直接退出。

这比“全表一刀两半，然后再继续一刀两半”的纯二分更适合生产数据分布。  
因为真实世界里的差异通常不是均匀撒在整张表上，而是集中在少数批次、少数时间窗或少数主键范围里。

### Phase 3：子段收敛，何时继续多分段，何时切二分

每个子段先算自己的 `row_count + checksum`。  
之后的决策逻辑大致是：

- checksum 一致：直接跳过；
- 当前段已经小到低于 `bisectionThreshold`：本地精确比较；
- 子段仍然足够大：继续多路切分；
- 否则：切换为真正的二分。

当前实现里，这两个阈值最关键：

- **进入本地比较**：`totalRows < bisectionThreshold`
- **从多分段切到二分**：`maxRows <= bisectionThreshold * bisectionFactor`

这套规则的本质，是尽量让“大而干净”的范围在前面快速退出，让“真正脏的热区”才走更深的递归。

## 为什么 `xor` 能成为默认更合理的选择

`concat` 和 `xor` 的差别，表面看是两种 checksum 算法，实质上是两种资源消耗模型。

`concat` 更直观，但通常需要：

- 对行 hash 排序；
- 通过 `GROUP_CONCAT` 或 `STRING_AGG` 聚成一个长字符串；
- 再做一次整体 hash。

这意味着排序、内存和临时表压力都比较重。

`xor` 则更像一套面向大表的工程折中：

- 先得到行 hash；
- 再做 XOR 聚合；
- 不依赖行顺序；
- 因而不需要 `ORDER BY`。

这不是说 `xor` 在数学上更“高级”，而是它更符合大表校验的基本目标：  
**尽量少做那些只为了结果稳定而引入的重操作。**

## `row-hash` 真正优化的是“精确比较阶段”

很多系统的瓶颈其实不在前面的 checksum，而在最后一步“把小段拉出来逐行比”。

Consilens 的 `row-hash` 本地比较模式，思路是先用一层更轻的行指纹做过滤。  
它不会一上来就查完整行，而是先查：

```text
主键 + row_hash
```

这里的 `row_hash` 不是随便拼出来的字符串。  
当前方言接口里，对 row-hash SQL 的要求非常明确：

- 列值先做规范化；
- 列之间用 ASCII 31 作为分隔符；
- `NULL` 用 ASCII 1 作为哨兵值，而不是直接当空串；
- 再对整行 canonical representation 计算 MD5。

这几个细节背后各有原因。分隔符用 ASCII 31，是因为普通分隔符（比如 `|`）可能出现在业务数据里，拼接结果就会有歧义；ASCII 31 几乎不会出现在正常文本里，更适合做 canonical row boundary。NULL 用 ASCII 1 做哨兵而不是压成空串，是因为空串和 NULL 在业务语义上通常不是同一件事——压平了会丢信息，校验结果就失准了。至于“先拉指纹再拉完整行”——生产上更常见的情况是一个小段里绝大多数行都没问题，先过滤一遍指纹，只对不一致的主键再查完整行，能明显减少精确阶段的数据传输量。

所以 `row-hash` 不是用来替代 checksum 的，而是用来优化 checksum 之后那一步“精确定位”。

## 跨数据库真正难的地方，在方言和规范化

表面上看，跨库校验像是在做“同一个 SQL 的多数据库版本”。  
实际上复杂得多，因为数据库差异不是一层，而是好几层叠在一起：

- checksum SQL 写法不一样；
- 时间格式化函数不一样；
- 布尔值转字符串的方式不一样；
- 数值精度表达不一样；
- 元数据查询和 schema 处理也不一样。

所以 `DatabaseDialect` 没有设计成一个“万能大类”，而是进一步拆成多个职责明确的组件：

- `SqlQueryGenerator`
- `MetadataQueryGenerator`
- `DataTypeHandler`
- `TransactionManager`
- `CapabilityProvider`
- `ConnectionPoolOptimizer`

这个设计的核心价值是：**数据库差异被拆散了，而不是被堆在一起。**

举个最直接的例子。  
同样是“把时间列规范成字符串”，MySQL 更可能走 `DATE_FORMAT()`，PostgreSQL 则要走 `TO_CHAR()`；  
同样是“把布尔值标准化成 1/0”，两边的表达式也完全不同。

如果这些逻辑直接写死在算法里，`ChecksumDiffer` 很快就会变成一个到处是数据库特判的怪物类。  
把这些差异下沉到方言层，算法才能维持相对稳定。

## 插件机制为什么重要

数据库支持不是编译期写死的，Consilens 用的是 JDK 原生 `ServiceLoader`。

运行时流程大概是：

1. CLI 根据 `source.type` / `target.type`，或者 JDBC URL，识别数据库类型；
2. `DialectFactory` 通过插件运行时加载 `DatabaseDialectProvider`；
3. Provider 创建对应的 `DatabaseDialect`；
4. `DatabaseAdapter` 组合连接池和方言，向上暴露统一查询能力。

每个插件 jar 里都带着自己的 SPI 注册文件：

```text
META-INF/services/com.consilens.connector.api.DatabaseDialectProvider
```

这件事带来的直接好处是：  
**新增数据库支持时，不需要改 CLI 主流程，只要补一个插件模块并打进 `plugins/` 就行。**

## 为什么输出链路要单独抽出来

算法在意的是“差异是什么”，但工程流程在意的是“差异往哪儿去”。

Consilens 把输出做成单独的 sink 子系统，内置了：

- `console`
- `json`
- `csv`
- `table`

而且支持两类数据：

- `diff-record`
- `result`

其中一个很值得注意的实现细节是：  
`DefaultDiffLifecycle` 被放在 `consilens-sink-api`，专门负责把生命周期事件桥接到 `SinkManager`。这么放不是随手为之，而是为了**避免 `consilens-core` 和 sink 模块之间形成循环依赖**。

这意味着算法层只需要在合适的时机发出事件：

- `onDiffStart`
- `onSegmentComplete`
- `onDifferencesFound`
- `onDiffComplete`
- `onDiffError`

至于这些事件最终是打印到控制台、写到 JSON 文件，还是落到数据库表，算法并不关心。

这也是为什么 Consilens 能做到“输出能力丰富，但主流程并不显得很重”。

## 并发模型：IO 和 CPU 分开，不让两类工作互相拖累

一致性校验天然是混合负载：

- 一部分工作是数据库访问、checksum 查询、数据拉取，明显偏 IO；
- 另一部分工作是本地差异计算、结果组装、对象转换，更偏 CPU。

如果这两类任务放在一个线程池里，慢查询很容易把本地计算拖住，反过来 CPU 密集任务也可能把数据库查询饿死。

Consilens 的做法是把 IO 和 CPU 线程池分开配置。  
这样至少能保证一件事：数据库端慢，不会直接把应用侧所有执行路径都堵死。

## `infoTree` 为什么有价值

很多校验工具在结果里只告诉你“有几条差异”。  
这对真正排障并不够。

Consilens 的 `DiffResult` 里除了差异明细和统计信息，还有一棵执行树 `infoTree`。  
它记录的不只是结果，还包括过程：

- 当前分段范围；
- 两侧行数；
- split 类型；
- checksum 是否命中；
- 是否进入本地比较；
- 执行耗时。

对于大表校验来说，这棵树非常有用。  
因为很多时候你要排查的不只是“数据哪里错了”，还有“为什么这次校验跑得这么慢”。

## 代码里明确写死的边界，也是一种架构选择

有些限制不是“以后再说”，而是当前实现明确规定的行为：

- `join` 会校验两张表是否来自**同一个 JDBC URL**，否则直接报错；
- `strategy.mode=local` 当前会在配置校验阶段被拒绝；
- `diff` 服务里对 `LOCAL` 也保留了未实现保护；
- `config validate --test-connection` 现在只会打印“not yet implemented”。

这些限制看起来不够完美，但它们至少保证了一件事：  
**系统不会把不成立的假设悄悄带进执行阶段。**

对于一个做数据校验的系统来说，清楚地暴露边界，往往比“表面上什么都支持”更可靠。

## 这套架构想守住的，其实只有一件事

从外面看，Consilens 像是在做“跨库 diff”。  
从里面看，它真正想守住的是一条更重要的原则：

**算法负责收敛问题，方言负责保证可比，输出负责承接结果。**

只有这三件事不缠在一起，系统才有可能在后续继续长下去：

- 要扩数据库，不必动算法；
- 要改输出，不必改方言；
- 要调优 checksum 收敛策略，也不用推倒整个 CLI。

这也是为什么 Consilens 的技术实现看起来不像一个“大而全”的框架，反而更像一组边界清楚的模块。  
做这类基础工具，很多时候克制比堆功能更重要。
