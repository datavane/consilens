package com.consilens.benchmark.e2e;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * e2e 场景编号到 examples yaml 路径的映射。场景编号稳定，避免基线失效。
 *
 * <p>复用 examples 既有配置，不复制数据集；examples 目录可用环境变量
 * {@code CONSILENS_EXAMPLES_DIR} 覆盖，默认相对当前工作目录。
 */
public final class ScenarioLoader {

    /** Kind marker for micro-benchmark results (JMH throughput scenarios). */
    public static final String KIND_MICRO = "micro";
    /** Kind marker for end-to-end scenarios loaded from this class. */
    public static final String KIND_E2E = "e2e";

    private static final String DEFAULT_E2E_DIR = "consilens-benchmark/src/main/resources/e2e";

    public static final String E02 = "E02";
    public static final String E03 = "E03";
    public static final String JOIN = "join";
    public static final String GENERATED = "G01";
    public static final String GENERATED_POSTGRESQL = "G02";
    public static final String CHECKSUM_CONCAT = "C-CONCAT";
    public static final String CHECKSUM_ROW_HASH = "C-ROW-HASH";
    public static final String CHECKSUM_FULL = "C-FULL";
    public static final String CHECKSUM_COMPOSITE = "C-COMPOSITE";
    public static final String JOIN_WHOLE = "J-WHOLE";
    public static final String JOIN_SEGMENTED = "J-SEGMENTED";

    private static final List<String> DEFAULT_SCENARIOS =
            Collections.unmodifiableList(Arrays.asList(GENERATED, CHECKSUM_CONCAT,
                    CHECKSUM_ROW_HASH, CHECKSUM_FULL, CHECKSUM_COMPOSITE,
                    JOIN_WHOLE, JOIN_SEGMENTED));

    private final Path examplesDir;

    public ScenarioLoader() {
        this(examplesDir());
    }

    public ScenarioLoader(Path examplesDir) {
        this.examplesDir = examplesDir;
    }

    public static Path examplesDir() {
        String override = System.getenv("CONSILENS_EXAMPLES_DIR");
        return Paths.get(override == null || override.trim().isEmpty() ? "examples" : override.trim());
    }

    /** Directory holding bundled benchmark yaml configs; overridable via {@code CONSILENS_BENCHMARK_E2E_DIR}. */
    public static Path e2eDir() {
        String override = System.getenv("CONSILENS_BENCHMARK_E2E_DIR");
        return Paths.get(override == null || override.trim().isEmpty() ? DEFAULT_E2E_DIR : override.trim());
    }

    public List<String> defaultScenarioIds() {
        return DEFAULT_SCENARIOS;
    }

    /**
     * 返回场景定义；未知场景编号返回 {@code null}。
     */
    public Scenario scenario(String id) {
        switch (id) {
            case E02:
                return new Scenario(E02, examplesDir.resolve("cross-db/mysql-pg/03-large-table.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD", "PG_USER", "PG_PASSWORD"),
                        dimensions("checksum", "concat", "continuous", "mixed"));
            case E03:
                return new Scenario(E03, examplesDir.resolve("cross-db/mysql-pg/06-performance-full-fields.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD", "PG_USER", "PG_PASSWORD"),
                        dimensions("checksum", "concat", "continuous", "mixed"));
            case JOIN:
                return new Scenario(JOIN, examplesDir.resolve("same-db/mysql/02-join-diff.yaml"),
                        Arrays.asList("MYSQL_USER", "MYSQL_PASSWORD"),
                        dimensions("join", "whole-table", "continuous", "mixed"));
            case GENERATED:
                return new Scenario(GENERATED, generatedConfigPath(),
                        Arrays.asList("BENCHMARK_JDBC_URL", "BENCHMARK_DB_USER", "BENCHMARK_DB_PASSWORD"),
                        dimensions("checksum", "xor", "generated", "generated"));
            case GENERATED_POSTGRESQL:
                return new Scenario(GENERATED_POSTGRESQL, generatedPostgresqlConfigPath(),
                        Arrays.asList("BENCHMARK_JDBC_URL", "BENCHMARK_DB_USER", "BENCHMARK_DB_PASSWORD"),
                        dimensions("checksum", "xor", "generated", "generated"));
            case CHECKSUM_CONCAT:
                return generatedScenario(CHECKSUM_CONCAT, "generated-checksum-concat.yaml",
                        dimensions("checksum", "concat", "generated", "generated"));
            case CHECKSUM_ROW_HASH:
                return generatedScenario(CHECKSUM_ROW_HASH, "generated-row-hash.yaml",
                        dimensions("checksum", "xor+row-hash", "generated", "generated"));
            case CHECKSUM_FULL:
                return generatedScenario(CHECKSUM_FULL, "generated-full.yaml",
                        dimensions("checksum", "xor+full", "generated", "generated"));
            case CHECKSUM_COMPOSITE:
                return generatedScenario(CHECKSUM_COMPOSITE, "generated-composite-key.yaml",
                        dimensions("checksum", "xor+row-hash", "composite", "generated"));
            case JOIN_WHOLE:
                return generatedScenario(JOIN_WHOLE, "generated-join-whole.yaml",
                        dimensions("join", "whole-table", "generated", "generated"));
            case JOIN_SEGMENTED:
                return Scenario.unsupported(JOIN_SEGMENTED,
                        "core JoinDiffer currently has no segmented-join execution plan",
                        dimensions("join", "segmented", "generated", "generated"));
            default:
                return null;
        }
    }

    private Scenario generatedScenario(String id, String fileName, Map<String, String> dimensions) {
        return new Scenario(id, e2eDir().resolve(fileName),
                Arrays.asList("BENCHMARK_JDBC_URL", "BENCHMARK_DB_USER", "BENCHMARK_DB_PASSWORD"), dimensions);
    }

    private static Map<String, String> dimensions(String strategy, String algorithm,
                                                   String keyDistribution, String differenceType) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("strategy", strategy);
        dimensions.put("algorithm", algorithm);
        dimensions.put("keyDistribution", keyDistribution);
        dimensions.put("differenceType", differenceType);
        return dimensions;
    }

    private Path generatedConfigPath() {
        String override = System.getenv("CONSILENS_GENERATED_CONFIG");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        return e2eDir().resolve("generated-mysql.yaml");
    }

    private Path generatedPostgresqlConfigPath() {
        String override = System.getenv("CONSILENS_GENERATED_POSTGRESQL_CONFIG");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        return e2eDir().resolve("generated-postgresql.yaml");
    }

    /**
     * 单个 e2e 场景：编号、yaml 路径、运行所需的数据库凭据环境变量。
     */
    public static final class Scenario {

        private final String id;
        private final Path configPath;
        private final List<String> requiredEnvVars;
        private final Map<String, String> dimensions;
        private final String unsupportedReason;
        private final String kind;

        public Scenario(String id, Path configPath, List<String> requiredEnvVars) {
            this(id, configPath, requiredEnvVars, Collections.emptyMap());
        }

        public Scenario(String id, Path configPath, List<String> requiredEnvVars,
                        Map<String, String> dimensions) {
            this(id, configPath, requiredEnvVars, dimensions, null);
        }

        private Scenario(String id, Path configPath, List<String> requiredEnvVars,
                         Map<String, String> dimensions, String unsupportedReason) {
            this.id = id;
            this.configPath = configPath;
            this.requiredEnvVars = Collections.unmodifiableList(requiredEnvVars);
            this.dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
            this.unsupportedReason = unsupportedReason;
            this.kind = KIND_E2E;
        }

        public static Scenario unsupported(String id, String reason, Map<String, String> dimensions) {
            return new Scenario(id, null, Collections.emptyList(), dimensions, reason);
        }

        public String id() {
            return id;
        }

        public Path configPath() {
            return configPath;
        }

        public List<String> requiredEnvVars() {
            return requiredEnvVars;
        }

        public Map<String, String> dimensions() {
            return dimensions;
        }

        /** Explicit scenario kind ("micro" / "e2e"), used to derive baseline thresholds. */
        public String kind() {
            return kind;
        }

        public boolean supported() {
            return unsupportedReason == null;
        }

        public String unsupportedReason() {
            return unsupportedReason;
        }
    }
}
