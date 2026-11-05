package com.consilens.benchmark.micro;

import com.consilens.core.algorithm.LocalDiffEngine;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LocalDiffEngine 微基准：以"数据量 × 差异量"矩阵覆盖无差异与有差异两类场景。
 *
 * <p>规模由 {@code rows} 控制（默认 10w/50w/100w 三档），差异比例由 {@code diffRatio}
 * 控制（默认 0%/1%/5%/10% 四档，0 表示无差异即 M01，大于 0 即 M02）。两个维度都可通过
 * 命令行覆盖（见 BenchmarkOptions 的 --rows / --diff-ratio）。
 *
 * <p>被测对象为 {@link LocalDiffEngine#findDifferences}，纯内存调用，不触数据库。输入行集
 * 由 {@link BenchmarkFixtures} 以固定种子确定性构造，与单元测试共用同一份 fixture 逻辑。
 *
 * <p>{@code @Setup} 将 LocalDiffEngine 的 log4j2 logger 调至 WARN，消除每次调用 log.info
 * 的噪声；{@code @TearDown} 还原原级别。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
public class LocalDiffEngineBenchmark {

    private static final String TARGET_LOGGER = LocalDiffEngine.class.getName();
    private static final String KEY_COL = "id";
    private static final String EXTRA_COL = "payload";
    private static final long SEED = 42L;

    @Param({"100000", "500000", "1000000"})
    private int rows;

    /** 差异比例：0 表示两侧完全一致（M01），大于 0 表示按该比例修改 extra 列（M02）。 */
    @Param({"0.00", "0.01", "0.05", "0.10"})
    private String diffRatio;

    private List<Object[]> leftRows;
    private List<Object[]> rightRows;
    private List<String> keyCols;
    private List<String> extraCols;

    private LoggerContext loggerContext;
    private Level originalLevel;

    @Setup
    public void setup() {
        silenceLogger();

        final int keyColumns = 1;
        final int extraColumns = 1;
        final double ratio = Double.parseDouble(diffRatio);
        leftRows = BenchmarkFixtures.buildRows(rows, SEED, keyColumns, extraColumns);
        // 相同种子产出相同行集，ratio=0 时两侧完全一致（M01）
        rightRows = BenchmarkFixtures.buildRows(rows, SEED, keyColumns, extraColumns);
        // ratio>0 时右侧按该比例修改 extra 列，主键保持不变（M02）
        if (ratio > 0) {
            rightRows = BenchmarkFixtures.withDifferences(leftRows, ratio, SEED + 1);
        }

        keyCols = Arrays.asList(KEY_COL);
        extraCols = Arrays.asList(EXTRA_COL);
    }

    /** 行集比对：差异量由 diffRatio 决定，0 即无差异场景。 */
    @Benchmark
    public void runDiff() {
        LocalDiffEngine.findDifferences(leftRows, rightRows, keyCols, extraCols, keyCols, extraCols);
    }

    private void silenceLogger() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(TARGET_LOGGER);
        originalLevel = loggerConfig.getLevel();
        LoggerConfig newConfig = new LoggerConfig(TARGET_LOGGER, Level.WARN, false);
        config.addLogger(TARGET_LOGGER, newConfig);
        loggerContext.updateLoggers();
    }

    @TearDown
    public void tearDown() {
        if (loggerContext != null) {
            Configuration config = loggerContext.getConfiguration();
            if (originalLevel != null) {
                LoggerConfig restored = new LoggerConfig(TARGET_LOGGER, originalLevel, false);
                config.addLogger(TARGET_LOGGER, restored);
            } else {
                config.removeLogger(TARGET_LOGGER);
            }
            loggerContext.updateLoggers();
        }
    }
}
