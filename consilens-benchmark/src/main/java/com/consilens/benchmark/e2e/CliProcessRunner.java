package com.consilens.benchmark.e2e;

import lombok.Getter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 {@link ProcessBuilder} 拉起 CLI 子进程执行 diff，支持超时 kill，解析耗时与差异统计。
 *
 * <p>子进程继承父进程环境变量，凭据通过环境注入（yaml 内 {@code ${env.XXX}} 由 CLI 解析）。
 * stdout 与 stderr 合并捕获，避免大输出时管道阻塞。
 */
public final class CliProcessRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Operation duration:\\s*(\\d+)\\s*ms");
    private static final Pattern ROW_COUNT_PATTERN =
            Pattern.compile("Source Row Count:\\s*(\\d+)");
    private static final Pattern DIFF_COUNT_PATTERN =
            Pattern.compile("(Source missing rows|Target missing rows|Mismatched rows|Total differences):\\s*(\\d+)");
    private static final Pattern BENCHMARK_METRIC_PATTERN =
            Pattern.compile("BENCHMARK_METRIC\\s+([A-Za-z][A-Za-z0-9]*)=(-?\\d+(?:\\.\\d+)?)");

    private static final Map<String, String> DIFF_KEY_ALIASES = createDiffKeyAliases();

    private final Duration timeout;

    public CliProcessRunner() {
        this(DEFAULT_TIMEOUT);
    }

    public CliProcessRunner(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 执行 {@code java -jar <cliJar> diff --config <configPath>}，返回解析后的结果。
     */
    public RunResult run(Path cliJar, Path configPath) {
        long started = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "java", "-jar", cliJar.toString(), "diff", "--config", configPath.toString(),
                    "--benchmark-metrics");
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            return RunResult.failed(e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readFully(process.getInputStream(), output), "cli-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        int exitCode;
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                timedOut = true;
            }
            exitCode = process.exitValue();
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return RunResult.failed("interrupted while waiting for CLI process");
        }

        long wallClockMs = System.currentTimeMillis() - started;
        return RunResult.completed(exitCode, output.toString(), wallClockMs, timedOut);
    }

    private static void readFully(InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // 进程被 kill 或已退出时读流失败可忽略，已捕获输出足够诊断
        }
    }

    private static Map<String, Double> parseDiffCounts(String text) {
        if (text == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> counts = new LinkedHashMap<>();
        Matcher matcher = DIFF_COUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = DIFF_KEY_ALIASES.get(matcher.group(1));
            counts.put(key, Double.parseDouble(matcher.group(2)));
        }
        return counts;
    }

    static Map<String, Double> parseBenchmarkMetrics(String text) {
        if (text == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        Matcher matcher = BENCHMARK_METRIC_PATTERN.matcher(text);
        while (matcher.find()) {
            metrics.put(matcher.group(1), Double.parseDouble(matcher.group(2)));
        }
        return metrics;
    }

    private static Long parseFirstLong(String text, Pattern pattern) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static Map<String, String> createDiffKeyAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("Source missing rows", "sourceMissingRows");
        aliases.put("Target missing rows", "targetMissingRows");
        aliases.put("Mismatched rows", "mismatchedRows");
        aliases.put("Total differences", "totalDifferences");
        return Collections.unmodifiableMap(aliases);
    }

    /**
     * CLI 子进程执行结果：退出码、合并输出、墙钟耗时、解析出的指标。
     */
    @Getter
    public static final class RunResult {

        private final int exitCode;
        private final String output;
        private final long wallClockMs;
        private final boolean timedOut;
        private final Long durationMs;
        private final Long sourceRowCount;
        private final Map<String, Double> diffCounts;
        private final Map<String, Double> benchmarkMetrics;

        private RunResult(int exitCode, String output, long wallClockMs, boolean timedOut,
                          Long durationMs, Long sourceRowCount, Map<String, Double> diffCounts) {
            this.exitCode = exitCode;
            this.output = output;
            this.wallClockMs = wallClockMs;
            this.timedOut = timedOut;
            this.durationMs = durationMs;
            this.sourceRowCount = sourceRowCount;
            this.diffCounts = diffCounts;
            this.benchmarkMetrics = parseBenchmarkMetrics(output);
        }

        static RunResult completed(int exitCode, String output, long wallClockMs, boolean timedOut) {
            return new RunResult(exitCode, output, wallClockMs, timedOut,
                    parseFirstLong(output, DURATION_PATTERN),
                    parseFirstLong(output, ROW_COUNT_PATTERN),
                    parseDiffCounts(output));
        }

        static RunResult failed(String message) {
            return new RunResult(-1, message, 0L, false, null, null, Collections.emptyMap());
        }

        /**
         * 有效耗时：优先 CLI 内部记录的 Operation duration，缺失时回退到进程墙钟耗时。
         */
        public long effectiveDurationMs() {
            return durationMs != null ? durationMs : wallClockMs;
        }
    }
}
