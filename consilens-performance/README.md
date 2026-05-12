# consilens-performance

`consilens-performance` provides lightweight utilities for measuring Consilens
execution behavior in development and release validation.

The module is intended to be used as a library from tests, smoke checks, or
internal benchmark harnesses. It does not require a live database for synthetic
workloads.

## Core APIs

- `PerformanceTestRunner`: runs warmup and measured iterations, then returns a
  `PerformanceTestResult`.
- `PerformanceCollector`: records latency, throughput, errors, data volume, JVM
  resource usage, and thread pool statistics.
- `AdaptiveThreadPoolExecutor`: bounded adaptive executor with task metrics.
- `PerformanceReportGenerator`: writes Markdown and HTML reports.

## Minimal Example

```java
PerformanceTestConfig config = PerformanceTestConfig.builder()
        .testName("smoke")
        .warmupIterations(1)
        .testIterations(10)
        .concurrencyLevel(2)
        .monitoringIntervalMs(100)
        .build();

PerformanceTestRunner runner = new PerformanceTestRunner();
try {
    PerformanceTestResult result = runner.runTest(config, () ->
            PerformanceTestRunner.TestResult.success(1_000, 8_192, 0));

    new PerformanceReportGenerator()
            .generateMarkdownReport(result, "target/performance/smoke.md");
} finally {
    runner.shutdown();
}
```

## Validation

Run the module tests:

```bash
./mvnw -pl consilens-performance -am test
```

The tests cover configuration validation, smoke execution, failure collection,
executor shutdown behavior, and report generation.
