# SDK 嵌入指南

`consilens-sdk` 是面向平台集成的薄 facade：它包装现有 `DefaultCompareRuntime`，补充结果后处理、预算、监听器和 CI verdict，不重新实现比对算法。

## 一、依赖

```xml
<dependency>
  <groupId>com.consilens</groupId>
  <artifactId>consilens-sdk</artifactId>
  <version>${consilens.version}</version>
</dependency>
```

连接器通过 `ServiceLoader` 发现。使用 JDBC 连接器时再加入 `consilens-connector-all`（或按需引入单个连接器模块）；文件源使用 `consilens-connector-file`。

## 二、最小示例（文件源）

```java
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;
import com.consilens.sdk.ConsilensEngine;
import com.consilens.sdk.DefaultConsilensEngine;

import java.util.List;
import java.util.Map;

ConnectorConfig source = ConnectorConfig.builder()
        .type("file")
        .name("source")
        .resource(ResourceLocator.builder()
                .type("file")
                .path("data/source.csv")
                .options(Map.of("format", "csv"))
                .build())
        .build();

CompareRequest request = CompareRequest.builder()
        .source(source)
        .target(target) // 同样的 ConnectorConfig 结构
        .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
        .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
        .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
        .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
        .build();

try (ConsilensEngine engine = new DefaultConsilensEngine()) {
    DiffResult result = engine.execute(request);
    System.out.println(result.getStatistics().getTotalDifferences());
    System.out.println(result.getColumnStats());
    System.out.println(result.getFingerprint().getRunFingerprint());
}
```

JDBC 源把 `type` 换成 `mysql`/`postgresql` 等，并在 `connection` map 中放 `url`、`username`、`password`（与 CLI 配置一致，见 [配置详解](./02-配置详解.md)）。

## 三、结果字段

`DiffResult` 在原有 `differences` / `statistics` / `infoTree` 基础上增量提供：

| 字段 | 内容 |
| --- | --- |
| `columnStats` | 每列 insert/update/delete/差异率；数据来自完整结果，不是样本 |
| `schemaCompatibility` | 列动作、类型兼容、精度/JSON/时间/排序规则告警 |
| `fingerprint` | 配置指纹、profile 指纹、引擎版本、运行指纹；不含连接凭据 |
| `evidence` | plan 类型、查询数、扫描行数/字节、分段数、阶段耗时 |

### 每列的"差异率"有两个字段，分母不同

`ColumnStats` 里有两个看着几乎一样的比率，**分子分母都不一样**，选错会让报告里的"差异率"差好几倍
（同一列可能同时是 `100%` 和 `16.67%`）：

| 字段 | 公式 | 用途 |
| --- | --- | --- |
| `differenceRate` | `differenceCount / (sourceRowCount + targetRowCount) * 100`（**所有操作**：insert + update + delete；分母是**两侧行数之和**） | 对外报"这一列有多少比例的（两侧总）行有差异"，**推荐用它** |
| `differencePercentage` | `updateCount / mismatchRows * 100`（只统计 update，分母是**有差异的行数**） | 历史字段，保留给旧版 UI 兼容；同名易混，新代码不要用 |

```java
// 推荐：按行算的差异率
double rate = columnStat.getDifferenceRate();
```

例：两侧各 3 行、2 个比较列、其中 `amount` 有 1 行 update（`examples/files/data`）——实测输出：

```
amount: differenceCount=1, differencePercentage=100.0, differenceRate=16.6667
name:   differenceCount=0, differencePercentage=0.0,   differenceRate=0.0
```

- `differencePercentage = 1/1*100 = 100.0`：有差异的 1 行里，这一列全部是 update；
- `differenceRate = 1/(3+3)*100 = 16.6667`：分母是**两侧行数之和**，不是单侧行数。

⚠️ 因为分母是两侧之和，**单侧 3 行里改 1 行会读成 16.67% 而不是 33%**。

### `truncated` 与 `aggregatedFromSamples` 是"保守标记"

两个布尔位都在说"这份统计可能不完整"，取值偏保守，误报方向是"多标"而不是"漏标"：

| 字段 | 何时为 `true` | 说明 |
| --- | --- | --- |
| `truncated` | `differenceCount >= maxDifferences` | **恰好等于上限也会标 `true`**。实现里接收端命中上限是**抛异常**而不是裁剪，所以正常返回的结果其实完整；但"刚好 N 条、N 等于上限"无法自证没有更多，故按保守口径标出 |
| `aggregatedFromSamples` | 该列统计由采样聚合而来 | 为 `true` 时 counts 是估计值，不是逐行精确统计 |

写报告时建议这样用：`truncated = true` 表示"结果**可能**不完整"，不要直接断言"被截断"；
需要确定性时把 `maxDifferences` 调大后重跑。这不是 bug（insert/delete
只在一侧存在，用两侧之和才不会超过 100%），但写报告时要按这个口径解释，否则会被当成"差异很少"。

这些都是新增字段，旧调用方不需要修改。

## 四、预算与监听器

```java
RunOptions options = RunOptions.builder()
        .maxDifferences(100_000)
        .maxDurationMs(60_000)
        .maxRowsScanned(10_000_000)
        .maxBytesFetched(512L * 1024 * 1024)
        .listener(new RunListener() {
            @Override
            public void onStart(CompareRequest request) { }

            @Override
            public void onProgress(CompareProgress progress) {
                // 流式计划周期性上报；其他计划可能不触发
            }

            @Override
            public void onComplete(CompareRequest request, DiffResult result, long durationMs) { }

            @Override
            public void onError(CompareRequest request, Throwable error, long durationMs) { }
        })
        .build();

DiffResult result = engine.execute(request, options);
```

- 未设置的预算回落到 `CompareRequest.executionOptions`；`maxDifferences` 未设置时默认 1,000,000。
- 预算超限会以 `DiffSdkException` 抛出，底层是 `BudgetExceededException`。
- 监听器是生命周期回调，异常会被忽略，不会中断比对；`onProgress` 目前由流式合并计划周期性触发并在结束时再触发一次，其他计划可能不触发。
- `ProgressListener`/`CompareProgress` 放在 `connector-api`，运行时通过 `CompareExecutionOptions` 传递；该字段标记为 `@JsonIgnore`，不会进入集群任务或配置 artifact。

## 五、CI verdict

```java
VerifyResult verify = engine.verify(request, VerifyPolicy.builder()
        .failOn(FailOn.NEW)
        .baselineFile(Path.of("baseline.jsonl"))
        .maxMismatchRate(0.5)
        .build(), RunOptions.defaults());

int exitCode = verify.getExitCode(); // 0/1/2/3/4
```

`verify` 与 CLI `verify` 使用同一套 `VerifyEvaluator` 和退出码契约。

## 六、稳定性约定

- `EngineInfo.API_VERSION` 当前为 `1.0`；只有破坏性变更才提升主版本。
- `ConsilensEngine`、`RunOptions`、`EngineInfo`、`RunListener`、`VerifyPolicy`/`VerifyResult`/`Verdict` 属于稳定 SDK 表面。
- `DiffResult` 只做增量字段扩展；不替换已有字段，不改变既有 getter 语义。
- `Verdict` 退出码固定：PASS=0、FAIL=1、ERROR=2、INCONCLUSIVE=3、BUDGET_EXCEEDED=4。
- `SdkApiCompatibilityTest` 在构建时守卫上述方法签名，删除/改签名会导致测试失败。

## 七、线程与资源

- `ConsilensEngine` 本身不持有连接池；每次 `execute` 按请求打开/关闭连接器。
- 同一个引擎实例可以被多个线程调用；底层 runtime 与注册表是线程安全的。
- 结果对象不可变地共享 `differences` 列表；需要修改时用 `toBuilder()`。
