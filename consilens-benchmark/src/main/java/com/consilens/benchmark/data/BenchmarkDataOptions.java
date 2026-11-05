package com.consilens.benchmark.data;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 命令行数据生成参数。 */
public final class BenchmarkDataOptions {

    private static final String DEFAULT_JDBC_URL = "jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1";
    private static final String DEFAULT_USERNAME = "sa";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_SOURCE_TABLE = "benchmark_source";
    private static final String DEFAULT_TARGET_TABLE = "benchmark_target";
    private static final long DEFAULT_ROWS = 1_000_000L;
    private static final double DEFAULT_DIFF_RATIO = 0.05;
    private static final double DEFAULT_SOURCE_MISSING_RATIO = 0.0;
    private static final double DEFAULT_TARGET_MISSING_RATIO = 0.0;
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_BATCH_SIZE = 5_000;
    private static final boolean DEFAULT_RESUME = false;
    private static final Path DEFAULT_REPORT_PATH = Paths.get("target/benchmark/data-report.json");
    private static final String DEFAULT_KEY_DISTRIBUTION = "continuous";

    /**
     * Largest sequence whose square still fits in a long; skewed business keys use
     * {@code sequence * sequence}, so rows must stay within this bound.
     */
    static final long MAX_SKEWED_ROWS = 3_037_000_499L;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String sourceTable;
    private final String targetTable;
    private final long rows;
    private final double diffRatio;
    private final double sourceMissingRatio;
    private final double targetMissingRatio;
    private final long seed;
    private final int batchSize;
    private final boolean resume;
    private final Path reportPath;
    private final String keyDistribution;

    private BenchmarkDataOptions(String jdbcUrl, String username, String password,
                                 String sourceTable, String targetTable, long rows,
                                 double diffRatio, double sourceMissingRatio,
                                 double targetMissingRatio, long seed, int batchSize,
                                 boolean resume, Path reportPath, String keyDistribution) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.rows = rows;
        this.diffRatio = diffRatio;
        this.sourceMissingRatio = sourceMissingRatio;
        this.targetMissingRatio = targetMissingRatio;
        this.seed = seed;
        this.batchSize = batchSize;
        this.resume = resume;
        this.reportPath = reportPath;
        this.keyDistribution = keyDistribution;
    }

    public static BenchmarkDataOptions parse(String[] args) {
        String jdbcUrl = valueFromEnv("BENCHMARK_JDBC_URL", DEFAULT_JDBC_URL);
        String username = valueFromEnv("BENCHMARK_DB_USER", DEFAULT_USERNAME);
        String password = valueFromEnv("BENCHMARK_DB_PASSWORD", DEFAULT_PASSWORD);
        String sourceTable = DEFAULT_SOURCE_TABLE;
        String targetTable = DEFAULT_TARGET_TABLE;
        long rows = DEFAULT_ROWS;
        double diffRatio = DEFAULT_DIFF_RATIO;
        double sourceMissingRatio = DEFAULT_SOURCE_MISSING_RATIO;
        double targetMissingRatio = DEFAULT_TARGET_MISSING_RATIO;
        long seed = DEFAULT_SEED;
        int batchSize = DEFAULT_BATCH_SIZE;
        boolean resume = DEFAULT_RESUME;
        Path reportPath = DEFAULT_REPORT_PATH;
        String keyDistribution = DEFAULT_KEY_DISTRIBUTION;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--jdbc-url":
                    jdbcUrl = next(args, ++i, arg);
                    break;
                case "--username":
                    username = next(args, ++i, arg);
                    break;
                case "--password":
                    password = next(args, ++i, arg);
                    break;
                case "--source-table":
                    sourceTable = next(args, ++i, arg);
                    break;
                case "--target-table":
                    targetTable = next(args, ++i, arg);
                    break;
                case "--rows":
                    rows = Long.parseLong(next(args, ++i, arg));
                    break;
                case "--diff-ratio":
                    diffRatio = Double.parseDouble(next(args, ++i, arg));
                    break;
                case "--source-missing-ratio":
                    sourceMissingRatio = Double.parseDouble(next(args, ++i, arg));
                    break;
                case "--target-missing-ratio":
                    targetMissingRatio = Double.parseDouble(next(args, ++i, arg));
                    break;
                case "--seed":
                    seed = Long.parseLong(next(args, ++i, arg));
                    break;
                case "--batch-size":
                    batchSize = Integer.parseInt(next(args, ++i, arg));
                    break;
                case "--report":
                    reportPath = Paths.get(next(args, ++i, arg));
                    break;
                case "--resume":
                    resume = true;
                    break;
                case "--key-distribution":
                    keyDistribution = next(args, ++i, arg).trim().toLowerCase();
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        validate(rows, diffRatio, sourceMissingRatio, targetMissingRatio, batchSize,
                sourceTable, targetTable, keyDistribution);
        return new BenchmarkDataOptions(jdbcUrl, username, password, sourceTable, targetTable,
                rows, diffRatio, sourceMissingRatio, targetMissingRatio, seed, batchSize,
                resume, reportPath, keyDistribution);
    }

    private static void validate(long rows, double diffRatio, double sourceMissingRatio,
                                 double targetMissingRatio, int batchSize,
                                 String sourceTable, String targetTable, String keyDistribution) {
        if (rows < 1 || batchSize < 1) {
            throw new IllegalArgumentException("rows and batch-size must be positive");
        }
        validateRatio("diff-ratio", diffRatio);
        validateRatio("source-missing-ratio", sourceMissingRatio);
        validateRatio("target-missing-ratio", targetMissingRatio);
        if (sourceTable.equalsIgnoreCase(targetTable)) {
            throw new IllegalArgumentException("source-table and target-table must differ");
        }
        if (!"continuous".equals(keyDistribution) && !"sparse".equals(keyDistribution)
                && !"skewed".equals(keyDistribution)) {
            throw new IllegalArgumentException("key-distribution must be continuous, sparse or skewed");
        }
        if ("skewed".equals(keyDistribution) && rows > MAX_SKEWED_ROWS) {
            throw new IllegalArgumentException("rows must be <= " + MAX_SKEWED_ROWS
                    + " for skewed key distribution (sequence*sequence would overflow)");
        }
    }

    private static void validateRatio(String name, double ratio) {
        if (ratio < 0 || ratio > 1 || Double.isNaN(ratio)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }

    private static String valueFromEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getSourceTable() { return sourceTable; }
    public String getTargetTable() { return targetTable; }
    public long getRows() { return rows; }
    public double getDiffRatio() { return diffRatio; }
    public double getSourceMissingRatio() { return sourceMissingRatio; }
    public double getTargetMissingRatio() { return targetMissingRatio; }
    public long getSeed() { return seed; }
    public int getBatchSize() { return batchSize; }
    public boolean isResume() { return resume; }
    public Path getReportPath() { return reportPath; }
    public String getKeyDistribution() { return keyDistribution; }
}
