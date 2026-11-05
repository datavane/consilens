package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.normalization.DefaultNormalizationSpecValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例配置覆盖矩阵：确保 examples 下的配置能覆盖配置模型的所有字段、
 * 所有枚举取值与所有字段类型，并保证种子数据刻意差异的完整性。
 */
class ExampleCoverageMatrixTest {

    private static final List<String> EXPECTED_PAIRS = List.of(
            "mysql-pg", "mysql-clickhouse", "mysql-tidb", "mysql-starrocks",
            "mysql-doris", "mysql-trino", "mysql-presto", "mysql-oceanbase",
            "mysql-oracle", "mysql-sqlserver");

    private static final List<String> EXPECTED_SCENARIOS = List.of(
            "01-custom-sql-checksum.yaml",
            "02-detail-to-aggregate.yaml",
            "03-large-table.yaml",
            "04-mapped-checksum.yaml",
            "05-same-db-join.yaml",
            "06-performance-full-fields.yaml",
            "07-exclude-fields.yaml",
            "09-json-format.json",
            "11-identical.yaml");

    private static final List<String> EXPECTED_SAME_DB = List.of(
            "mysql", "postgresql", "clickhouse", "tidb", "starrocks", "doris",
            "trino", "presto", "oceanbase", "oracle", "sqlserver");

    private static final List<String> SAME_DB_SCENARIOS = List.of(
            "01-identical.yaml",
            "02-join-diff.yaml");

    private static final Set<String> FULL_COMPARISON_COLUMNS = Set.of(
            "col_tinyint", "col_smallint", "col_mediumint", "col_int", "col_bigint",
            "col_unsigned_int", "col_float", "col_double", "col_decimal", "col_numeric",
            "col_char", "col_varchar_50", "col_varchar_100", "col_varchar_255", "col_text",
            "col_mediumtext", "col_binary", "col_varbinary", "col_blob", "col_date",
            "col_datetime", "col_timestamp", "col_time", "col_boolean", "col_tinyint_bool",
            "col_enum", "col_set", "col_json", "user_name", "email", "phone", "address",
            "city", "country", "postal_code", "amount", "balance", "credit_limit", "status",
            "category", "priority", "score", "created_at", "updated_at");

    private static final Map<String, Set<String>> TYPE_FAMILY_COLUMNS = Map.of(
            "integral", Set.of("col_tinyint", "col_smallint", "col_mediumint", "col_int", "col_bigint",
                    "col_unsigned_int"),
            "floating", Set.of("col_float", "col_double", "score"),
            "decimal", Set.of("col_decimal", "col_numeric", "amount", "balance", "credit_limit"),
            "string", Set.of("col_char", "col_varchar_50", "col_text"),
            "binary", Set.of("col_binary", "col_varbinary", "col_blob"),
            "temporal", Set.of("col_date", "col_datetime", "col_timestamp", "col_time"),
            "boolean", Set.of("col_boolean", "col_tinyint_bool"),
            "semi-structured", Set.of("col_json"),
            "vendor-special", Set.of("col_enum", "col_set"));

    private static final Pattern CREATE_PERFORMANCE_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+[^;]*?consilens_performance_demo_table\\s*\\(");

    private static Path examplesDirectory() {
        try {
            Path testClasses = Paths.get(ExampleCoverageMatrixTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path examples = testClasses.getParent().getParent().getParent().resolve("examples").normalize();
            if (!Files.isDirectory(examples)) {
                throw new IllegalStateException("examples directory not found at: " + examples);
            }
            return examples;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate examples directory from test classes location", e);
        }
    }

    private static List<Path> allExampleConfigs() throws IOException {
        try (Stream<Path> stream = Files.walk(examplesDirectory())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .filter(path -> !path.getFileName().toString().equals("test-baselines.json"))
                    .filter(path -> !path.toString().endsWith(".bak"))
                    .filter(path -> !path.toString().contains("_archive"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<JsonNode> parseAllConfigs() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return allExampleConfigs().stream()
                    .map(path -> {
                        try {
                            return mapper.readTree(path.toFile());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allExampleConfigs")
    void shouldLoadEveryExampleConfiguration(Path configPath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());
        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        assertNotNull(config, "Config should load: " + configPath);
        assertNotNull(config.getSource(), "Source config should not be null: " + configPath);
        assertNotNull(config.getTarget(), "Target config should not be null: " + configPath);
        assertNotNull(config.getComparison(), "Comparison config should not be null: " + configPath);
        assertNotNull(config.getComparison().getKeys(), "comparison.keys should not be null: " + configPath);
        config.validate();

        var request = new CompareRequestFactory().create(config);
        assertNotNull(request, "Config should compile to CompareRequest: " + configPath);
        if (request.getNormalizationSpec() != null) {
            new DefaultNormalizationSpecValidator().validate(request.getNormalizationSpec());
        }
    }

    @Test
    void shouldCoverAllConfigurationFieldsAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        for (String requiredPath : configurationFieldPaths()) {
            assertTrue(configs.stream().anyMatch(node -> hasPath(node, requiredPath)),
                    "examples 目录缺少配置字段覆盖: " + requiredPath);
        }
    }

    @Test
    void shouldDeriveNestedCollectionAndMapFieldsFromConfigurationModel() {
        Set<String> paths = configurationFieldPaths();
        assertTrue(paths.contains("comparison.mappings[*].name"), "未派生 mapping 元素字段");
        assertTrue(paths.contains("result.sinks[*].format"), "未派生 sink 元素字段");
        assertTrue(paths.contains("normalization.global.*.precision"), "未派生 normalization 规则字段");
    }

    @Test
    void shouldCoverAllEnumValuesAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        for (Map.Entry<String, Set<String>> entry : configurationEnumValues().entrySet()) {
            for (String value : entry.getValue()) {
                assertTrue(hasValue(configs, entry.getKey(), value),
                        "examples 目录缺少 " + entry.getKey() + "=" + value);
            }
        }
        assertTrue(hasValue(configs, "source.resource.type", "table"), "缺少 resource.type=table");
        assertTrue(hasValue(configs, "source.resource.type", "sql"), "缺少 resource.type=sql");

        Set<String> sinkFormats = new HashSet<>();
        Set<String> sinkTypes = new HashSet<>();
        Set<Boolean> sinkEnabled = new HashSet<>();
        for (JsonNode config : configs) {
            JsonNode sinks = config.at("/result/sinks");
            if (sinks == null || !sinks.isArray()) {
                continue;
            }
            for (JsonNode sink : sinks) {
                if (sink.hasNonNull("format")) {
                    sinkFormats.add(sink.get("format").asText());
                }
                if (sink.hasNonNull("type")) {
                    sinkTypes.add(sink.get("type").asText());
                }
                if (sink.has("enabled")) {
                    sinkEnabled.add(sink.get("enabled").asBoolean());
                }
            }
        }
        assertTrue(sinkFormats.containsAll(List.of("console", "csv", "json", "table")),
                "缺少 sink format 覆盖: " + sinkFormats);
        assertTrue(sinkTypes.containsAll(List.of("result", "diff-record")),
                "缺少 sink type 覆盖: " + sinkTypes);
        assertTrue(sinkEnabled.containsAll(List.of(true, false)),
                "缺少 sink enabled true/false 覆盖: " + sinkEnabled);
    }

    @Test
    void shouldCoverAllNormalizationRuleParameters() {
        List<JsonNode> configs = parseAllConfigs();
        List<String> requiredTokens = List.of(
                "precision", "rounding", "format", "timezone", "comparisonMode",
                "encoding", "uppercase", "trueValue", "falseValue", "nullValue");
        for (String token : requiredTokens) {
            boolean covered = configs.stream()
                    .filter(node -> node.has("normalization"))
                    .map(node -> node.get("normalization"))
                    .anyMatch(node -> anyFieldContainsKey(node, token));
            assertTrue(covered, "normalization 缺少规则参数覆盖: " + token);
        }
    }

    @Test
    void shouldCoverAllJsonValueTypesAcrossExamples() {
        List<JsonNode> configs = parseAllConfigs();
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isTextual)), "缺少 string 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isInt)), "缺少 int 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isLong)), "缺少 long 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isBoolean)), "缺少 boolean 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isArray)), "缺少 array 类型");
        assertTrue(configs.stream().anyMatch(node -> containsType(node, JsonNode::isObject)), "缺少 object 类型");
    }

    @Test
    void shouldHaveUniformScenarioSetPerPair() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path pairDir = examplesDirectory().resolve("cross-db").resolve(pair);
            assertTrue(Files.isDirectory(pairDir), "缺少 pair 目录: " + pair);
            for (String scenario : EXPECTED_SCENARIOS) {
                assertTrue(Files.exists(pairDir.resolve(scenario)), pair + " 缺少场景配置: " + scenario);
            }
            assertTrue(Files.exists(pairDir.resolve("load-mysql.sql")), pair + " 缺少 load-mysql.sql");
            assertTrue(targetLoadSql(pairDir) != null, pair + " 缺少目标端 load 脚本");
        }
        assertTrue(Files.exists(examplesDirectory().resolve("cross-db/mysql-pg/08-output-postgres.yaml")),
                "cross-db/mysql-pg 缺少 08-output-postgres.yaml（输出方言覆盖）");
        assertTrue(Files.exists(examplesDirectory().resolve("cross-db/mysql-doris/10-partition-filter.yaml")),
                "cross-db/mysql-doris 缺少 10-partition-filter.yaml（分区过滤覆盖）");
        for (String name : EXPECTED_SAME_DB) {
            Path sameDbDir = examplesDirectory().resolve("same-db").resolve(name);
            assertTrue(Files.isDirectory(sameDbDir), "same-db 缺少数据源目录: " + name);
            for (String scenario : SAME_DB_SCENARIOS) {
                assertTrue(Files.exists(sameDbDir.resolve(scenario)),
                        "same-db/" + name + " 缺少场景配置: " + scenario);
            }
            try (Stream<Path> stream = Files.list(sameDbDir)) {
                long loadSqlCount = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("load-"))
                        .count();
                assertTrue(loadSqlCount > 0, "same-db/" + name + " 缺少 load 脚本");
            }
        }
        assertTrue(Files.exists(examplesDirectory().resolve("same-db/mysql/03-normalization.yaml")),
                "same-db/mysql 缺少 03-normalization.yaml（normalization 全参数覆盖）");
    }

    @Test
    void shouldKeepSeedDataDifferencesConsistent() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path targetSql = targetLoadSql(examplesDirectory().resolve("cross-db").resolve(pair));
            String text = Files.readString(targetSql);
            assertTrue(text.contains("99999.9999"), pair + " 缺少 amount MISMATCH (REC0000000001)");
            assertTrue(text.contains("modified_status"), pair + " 缺少 status MISMATCH (REC0000000002)");
            assertTrue(text.contains("REC_EXTRA_001"), pair + " 缺少 SOURCE_MISSING (REC_EXTRA_001)");
            assertTrue(text.contains("n != 5") || text.contains("n <> 5") || text.contains("LEVEL <> 5")
                            || text.contains("n%5") || text.contains("n % 5"),
                    pair + " 缺少 TARGET_MISSING (跳过 n=5)");
        }
    }

    @Test
    void shouldCompareEverySeededTypeFamilyInsteadOfOnlyCreatingColumns() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        for (String pair : EXPECTED_PAIRS) {
            Path pairDirectory = examplesDirectory().resolve("cross-db").resolve(pair);
            JsonNode fullFieldConfig = mapper.readTree(pairDirectory.resolve("06-performance-full-fields.yaml").toFile());
            Set<String> sourceFields = jsonTextSet(fullFieldConfig.at("/comparison/fields/source"));
            Set<String> targetFields = jsonTextSet(fullFieldConfig.at("/comparison/fields/target"));

            assertEquals(FULL_COMPARISON_COLUMNS, sourceFields,
                    pair + " 的全字段场景未实际比较所有参与校验的 fixture 列");
            assertEquals(sourceFields, targetFields, pair + " 的源端和目标端比较字段不对称");
            for (Map.Entry<String, Set<String>> family : TYPE_FAMILY_COLUMNS.entrySet()) {
                assertTrue(sourceFields.containsAll(family.getValue()),
                        pair + " 未覆盖类型族 " + family.getKey() + ": " + family.getValue());
            }

            Set<String> sourceColumns = performanceTableColumns(pairDirectory.resolve("load-mysql.sql"));
            Set<String> targetColumns = performanceTableColumns(targetLoadSql(pairDirectory));
            assertTrue(sourceColumns.containsAll(FULL_COMPARISON_COLUMNS),
                    pair + " 的源端 DDL 缺少全字段配置引用列");
            assertTrue(targetColumns.containsAll(FULL_COMPARISON_COLUMNS),
                    pair + " 的目标端 DDL 缺少全字段配置引用列");
        }
    }

    @Test
    void shouldBindFourAccuracyOutcomesToStableRowsAndFields() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        for (String pair : EXPECTED_PAIRS) {
            Path pairDirectory = examplesDirectory().resolve("cross-db").resolve(pair);
            String targetSql = Files.readString(targetLoadSql(pairDirectory));
            // 差异行的构造方式随方言能力而异：支持 UPDATE 的方言用 UPDATE ... WHERE，
            // StarRocks 的 DUPLICATE KEY 表不支持 UPDATE，改用插入时的内联表达式
            // （IF(n=1, 99999.9999, ...) / CASE WHEN n=2 THEN 'modified_status'）。
            assertSqlMatches(targetSql,
                    "(?:UPDATE\\s+amount|SET\\s+amount)\\s*=\\s*99999\\.9999\\s+WHERE\\s+record_id\\s*=\\s*'REC0000000001'"
                            + "|IF\\(n\\s*=\\s*1,\\s*99999\\.9999",
                    pair + " 缺少可定位到 amount 字段的值不一致行");
            assertSqlMatches(targetSql,
                    "(?:UPDATE\\s+status|SET\\s+status)\\s*=\\s*'modified_status'\\s+WHERE\\s+record_id\\s*=\\s*'REC0000000002'"
                            + "|WHEN\\s+n\\s*=\\s*2\\s+THEN\\s+'modified_status'",
                    pair + " 缺少可定位到 status 字段的值不一致行");
            assertSqlMatches(targetSql, "REC_EXTRA_001",
                    pair + " 缺少只存在于目标端的 SOURCE_MISSING 行");
            assertTrue(excludesSequenceFive(targetSql), pair + " 缺少只存在于源端的 TARGET_MISSING 行");

            JsonNode identical = mapper.readTree(pairDirectory.resolve("11-identical.yaml").toFile());
            String sourceFilter = identical.at("/comparison/filters/source").asText();
            String targetFilter = identical.at("/comparison/filters/target").asText();
            assertEquals(sourceFilter, targetFilter, pair + " 完全一致场景的两端过滤范围不相同");
            assertTrue(sourceFilter.contains("REC0000000006") && sourceFilter.contains("REC0000000999"),
                    pair + " 完全一致场景没有排除已知差异键并保留稳定数据区间");
            assertEquals(jsonTextSet(identical.at("/comparison/fields/source")),
                    jsonTextSet(identical.at("/comparison/fields/target")),
                    pair + " 完全一致场景的两端字段不对称");
        }
    }

    @Test
    void shouldKeepMappedAndJoinScenariosBackedBySeedDifferences() throws IOException {
        for (String pair : EXPECTED_PAIRS) {
            Path targetSql = targetLoadSql(examplesDirectory().resolve("cross-db").resolve(pair));
            String text = Files.readString(targetSql);
            assertTrue(text.contains("modified@example.com"),
                    pair + " 的 04-mapped-checksum 需要 users email MISMATCH");
            assertTrue(text.contains("orders_backup") && text.contains("99999.9999"),
                    pair + " 的 05-same-db-join 需要 orders_backup amount MISMATCH");
            assertTrue(text.contains("10001"),
                    pair + " 的 05-same-db-join 需要 orders 额外订单 (SOURCE_MISSING)");
        }
    }

    @Test
    void shouldKeepBaselineFileInSyncWithExamples() throws Exception {
        Path baselineFile = examplesDirectory().resolve("test-baselines.json");
        assertTrue(Files.exists(baselineFile), "缺少 test-baselines.json");
        JsonNode baselines = new ObjectMapper().readTree(baselineFile.toFile()).get("baselines");

        Set<String> expectedKeys = allExampleConfigs().stream()
                .map(path -> examplesDirectory().relativize(path).toString().replace('\\', '/'))
                .collect(Collectors.toSet());
        Set<String> actualKeys = new HashSet<>();
        baselines.fieldNames().forEachRemaining(actualKeys::add);

        Set<String> missing = new HashSet<>(expectedKeys);
        missing.removeAll(actualKeys);
        Set<String> extra = new HashSet<>(actualKeys);
        extra.removeAll(expectedKeys);
        assertTrue(missing.isEmpty(), "test-baselines.json 缺少条目: " + missing);
        assertTrue(extra.isEmpty(), "test-baselines.json 存在多余条目: " + extra);
    }

    private static Path targetLoadSql(Path pairDir) throws IOException {
        try (Stream<Path> stream = Files.list(pairDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("load-"))
                    .filter(path -> !path.getFileName().toString().equals("load-mysql.sql"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Set<String> jsonTextSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> performanceTableColumns(Path sqlPath) throws IOException {
        String sql = Files.readString(sqlPath);
        Matcher matcher = CREATE_PERFORMANCE_TABLE.matcher(sql);
        assertTrue(matcher.find(), sqlPath + " 缺少 consilens_performance_demo_table DDL");
        int bodyStart = matcher.end();
        int bodyEnd = matchingParenthesis(sql, bodyStart - 1);
        assertTrue(bodyEnd > bodyStart, sqlPath + " 的 performance table DDL 括号不完整");

        Set<String> columns = new HashSet<>();
        for (String declaration : splitTopLevel(sql.substring(bodyStart, bodyEnd))) {
            Matcher column = Pattern.compile("^\\s*[`\"\\[]?([A-Za-z_][A-Za-z0-9_]*)").matcher(declaration);
            if (column.find()) {
                String name = column.group(1).toLowerCase();
                if (!Set.of("primary", "constraint", "key", "unique", "index", "partition").contains(name)) {
                    columns.add(name);
                }
            }
        }
        return columns;
    }

    private static int matchingParenthesis(String text, int openingIndex) {
        int depth = 0;
        for (int index = openingIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> declarations = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                declarations.add(body.substring(start, index));
                start = index + 1;
            }
        }
        declarations.add(body.substring(start));
        return declarations;
    }

    private static void assertSqlMatches(String sql, String regex, String message) {
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find(), message);
    }

    private static boolean excludesSequenceFive(String sql) {
        return Pattern.compile("(?i)(?:n|LEVEL)\\s*(?:!=|<>)\\s*5|n\\s*%\\s*5").matcher(sql).find();
    }

    private static boolean hasPath(JsonNode node, String path) {
        return !nodesAtPath(node, path).isEmpty();
    }

    private static boolean hasValue(List<JsonNode> configs, String path, String value) {
        for (JsonNode config : configs) {
            if (nodesAtPath(config, path).stream().anyMatch(node -> node.asText().equals(value))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> configurationFieldPaths() {
        Set<String> paths = new HashSet<>();
        collectConfigurationFieldPaths(CliConfiguration.class, "", paths, new HashSet<>());
        // 列级归一化当前只开放数值容差（precision/timezone 等需要 SQL 侧同步实现）；
        // 其余字段由 validator 明确拒绝，不要求示例覆盖。
        paths.removeIf(path -> path.startsWith("normalization.columns.*.")
                && !"normalization.columns.*.tolerance".equals(path)
                && !"normalization.columns.*.relativeTolerance".equals(path));
        return paths;
    }

    private static void collectConfigurationFieldPaths(Class<?> type, String prefix, Set<String> paths,
            Set<Class<?>> ancestors) {
        if (!ancestors.add(type)) {
            return;
        }
        for (Field field : type.getDeclaredFields()) {
            if (!isConfigurationField(field)) {
                continue;
            }
            String path = joinPath(prefix, propertyName(field));
            paths.add(path);
            collectNestedFieldPaths(field.getGenericType(), path, paths, ancestors);
        }
        ancestors.remove(type);
    }

    private static void collectNestedFieldPaths(Type fieldType, String path, Set<String> paths,
            Set<Class<?>> ancestors) {
        Class<?> rawType = rawType(fieldType);
        if (rawType == null) {
            return;
        }
        if (Iterable.class.isAssignableFrom(rawType)) {
            Type elementType = typeArgument(fieldType, 0);
            collectNestedFieldPaths(elementType, path + "[*]", paths, ancestors);
            return;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            Type valueType = typeArgument(fieldType, 1);
            collectNestedFieldPaths(valueType, path + ".*", paths, ancestors);
            return;
        }
        if (isScalar(rawType) || rawType.isEnum()) {
            return;
        }
        collectConfigurationFieldPaths(rawType, path, paths, ancestors);
    }

    private static Map<String, Set<String>> configurationEnumValues() {
        Map<String, Set<String>> valuesByPath = new LinkedHashMap<>();
        collectConfigurationEnumValues(CliConfiguration.class, "", valuesByPath, new HashSet<>());
        return valuesByPath;
    }

    private static void collectConfigurationEnumValues(Class<?> type, String prefix,
            Map<String, Set<String>> valuesByPath, Set<Class<?>> ancestors) {
        if (!ancestors.add(type)) {
            return;
        }
        Map<String, Field> fieldsByProperty = new HashMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (isConfigurationField(field)) {
                fieldsByProperty.put(propertyName(field), field);
                Class<?> rawType = rawType(field.getGenericType());
                if (rawType != null && rawType.isEnum()) {
                    valuesByPath.put(joinPath(prefix, propertyName(field)), enumCodes(rawType));
                }
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            Class<?> enumType = method.getReturnType();
            String property = enumProperty(method);
            Field field = fieldsByProperty.get(property);
            if (property != null && enumType.isEnum() && isEnumInputField(field)) {
                valuesByPath.put(joinPath(prefix, property), enumCodes(enumType));
            }
        }
        for (Field field : fieldsByProperty.values()) {
            String path = joinPath(prefix, propertyName(field));
            collectNestedEnumValues(field.getGenericType(), path, valuesByPath, ancestors);
        }
        ancestors.remove(type);
    }

    private static void collectNestedEnumValues(Type fieldType, String path, Map<String, Set<String>> valuesByPath,
            Set<Class<?>> ancestors) {
        Class<?> rawType = rawType(fieldType);
        if (rawType == null) {
            return;
        }
        if (Iterable.class.isAssignableFrom(rawType)) {
            collectNestedEnumValues(typeArgument(fieldType, 0), path + "[*]", valuesByPath, ancestors);
            return;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            collectNestedEnumValues(typeArgument(fieldType, 1), path + ".*", valuesByPath, ancestors);
            return;
        }
        if (isScalar(rawType) || rawType.isEnum()) {
            return;
        }
        collectConfigurationEnumValues(rawType, path, valuesByPath, ancestors);
    }

    private static boolean isConfigurationField(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                || field.isAnnotationPresent(JsonIgnore.class) || field.isAnnotationPresent(JsonAnyGetter.class)) {
            return false;
        }
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        return property == null || property.access() != JsonProperty.Access.READ_ONLY;
    }

    private static String propertyName(Field field) {
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        return property != null && !property.value().isEmpty() ? property.value() : field.getName();
    }

    private static String enumProperty(Method method) {
        String name = method.getName();
        if (!name.startsWith("get") || !name.endsWith("Enum") || name.length() <= "getEnum".length()) {
            return null;
        }
        String property = name.substring(3, name.length() - "Enum".length());
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }

    private static boolean isEnumInputField(Field field) {
        return field != null && rawType(field.getGenericType()) == String.class;
    }

    private static Set<String> enumCodes(Class<?> enumType) {
        Set<String> codes = new HashSet<>();
        try {
            Method getCode = enumType.getMethod("getCode");
            for (Object value : enumType.getEnumConstants()) {
                codes.add(String.valueOf(getCode.invoke(value)));
            }
        } catch (ReflectiveOperationException e) {
            for (Object value : enumType.getEnumConstants()) {
                codes.add(((Enum<?>) value).name().toLowerCase());
            }
        }
        return codes;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            return raw instanceof Class<?> ? (Class<?>) raw : null;
        }
        return null;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length > index) {
                return arguments[index];
            }
        }
        return Object.class;
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type.getName().startsWith("java.") || type.getName().startsWith("javax.");
    }

    private static String joinPath(String prefix, String property) {
        return prefix.isEmpty() ? property : prefix + "." + property;
    }

    private static List<JsonNode> nodesAtPath(JsonNode node, String path) {
        List<JsonNode> current = Collections.singletonList(node);
        for (String segment : path.split("\\.")) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode candidate : current) {
                if ("*".equals(segment) && candidate.isObject()) {
                    candidate.elements().forEachRemaining(next::add);
                } else if (segment.endsWith("[*]")) {
                    JsonNode array = candidate.get(segment.substring(0, segment.length() - 3));
                    if (array != null && array.isArray()) {
                        array.elements().forEachRemaining(next::add);
                    }
                } else {
                    JsonNode child = candidate.get(segment);
                    if (child != null) {
                        next.add(child);
                    }
                }
            }
            current = next;
        }
        return current;
    }

    private static boolean anyFieldContainsKey(JsonNode node, String key) {
        if (node == null || !node.isObject()) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equals(key) || anyFieldContainsKey(entry.getValue(), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsType(JsonNode node, Predicate<JsonNode> predicate) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (predicate.test(node)) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsType(child, predicate)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                if (containsType(fields.next().getValue(), predicate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> testEnvironment() {
        Map<String, String> env = new HashMap<>();
        for (String name : List.of("MYSQL", "PG", "CLICKHOUSE", "TIDB", "STARROCKS",
                "DORIS", "ORACLE", "SQLSERVER", "TRINO", "PRESTO", "OCEANBASE")) {
            env.put(name + "_USER", "test_user");
            env.put(name + "_PASSWORD", "test_password");
        }
        return env;
    }
}
