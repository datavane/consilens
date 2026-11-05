package com.consilens.server.application.capability.config;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.sink.api.model.ResultConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ServerCompareConfigService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    /**
     * 与 CLI 的 {@code EnvironmentPlaceholderResolver} 对齐：支持 {@code ${env.VAR}} 与
     * {@code ${env.VAR:default}}。以前只认前者，文档里写了默认值写法的地方在服务端会原样把
     * 占位符当值传下去（连库时报"未知的 URL"）。
     */
    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;

    public ServerCompareConfigService(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public ServerCompareConfig fromPlanRequest(PlanRequest request) {
        requireEndpointResource("source", request.getSource());
        requireEndpointResource("target", request.getTarget());
        Map<String, Object> hints = copyMap(request.getHints());
        ServerCompareConfig config = ServerCompareConfig.builder()
                .goal(request.getGoal())
                .source(endpoint("source", request.getSource(), hints))
                .target(endpoint("target", request.getTarget(), hints))
                .keys(normalizedStrings(request.getKeys()))
                .comparison(comparison(hints))
                .hints(hints)
                .executionOptions(mapFrom(hints.get("executionOptions")))
                .build();
        validate(config);
        return config;
    }

    /**
     * Builds the comparison config from plan hints:
     * <ul>
     * <li>{@code compareColumns}: list of column names (compared by same name)
     * or list of {source, target} maps (one-to-one mappings)</li>
     * <li>{@code ignoreColumns}: column names excluded on both sides</li>
     * <li>{@code keyMappings}: list of {source, target} maps for primary keys
     * whose names differ between the two tables</li>
     * <li>{@code normalization}: CLI-shaped type rules ({@code global/source/target}),
     * compiled by the same {@code NormalizationSpecs} used by the CLI</li>
     * </ul>
     */
    private ComparisonConfig comparison(Map<String, Object> hints) {
        Object compareColumns = hints.get("compareColumns");
        boolean mappingForm = isMappingList(compareColumns);
        return ComparisonConfig.builder()
                .fields(mappingForm ? List.of() : listFrom(compareColumns))
                .ignoreColumns(listFrom(hints.get("ignoreColumns")))
                .fieldMappings(mappingForm ? parseMappings(compareColumns, "compareColumns") : List.of())
                .keyMappings(parseMappings(hints.get("keyMappings"), "keyMappings"))
                .normalization(normalizationFrom(hints.get("normalization")))
                .build();
    }

    private boolean isMappingList(Object raw) {
        if (!(raw instanceof List)) {
            return false;
        }
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a list of {source, target} maps. Malformed entries are rejected instead of
     * silently dropped: dropping one side would remove that column from the comparison
     * and produce a false "identical" verdict.
     */
    private List<FieldMapping> parseMappings(Object raw, String path) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new InvalidInputException(path + " must be a list of {source, target} objects");
        }
        List<FieldMapping> mappings = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                throw new InvalidInputException(
                        path + " must contain only {source, target} objects");
            }
            Map<?, ?> map = (Map<?, ?>) item;
            Object sourceValue = map.get("source");
            Object targetValue = map.get("target");
            String source = sourceValue == null ? null : trim(String.valueOf(sourceValue));
            String target = targetValue == null ? null : trim(String.valueOf(targetValue));
            if (isBlank(source) || isBlank(target)) {
                throw new InvalidInputException(path + " contains an incomplete mapping");
            }
            mappings.add(FieldMapping.builder().source(source).target(target).build());
        }
        return mappings;
    }

    public ServerCompareConfig fromValidateRequest(ValidateRequest request) {
        boolean hasArtifact = request.getConfigArtifactId() != null && !request.getConfigArtifactId().isBlank();
        boolean hasContent = request.getConfigContent() != null && !isBlankString(request.getConfigContent());
        if (hasArtifact == hasContent) {
            throw new InvalidInputException("configArtifactId and configContent must contain exactly one value");
        }
        if (hasArtifact) {
            return fromArtifact(request.getConfigArtifactId());
        }
        return fromContent(request.getConfigContent());
    }

    public ServerCompareConfig fromRunRequest(RunRequest request) {
        boolean hasArtifact = request.getConfigArtifactId() != null && !request.getConfigArtifactId().isBlank();
        boolean hasContent = request.getConfigContent() != null && !isBlankString(request.getConfigContent());
        if (hasArtifact == hasContent) {
            throw new InvalidInputException("configArtifactId and configContent must contain exactly one value");
        }
        return hasArtifact ? fromArtifact(request.getConfigArtifactId()) : fromContent(request.getConfigContent());
    }

    /**
     * 读取 CONFIG artifact。
     *
     * <p>artifact 里存的是**引擎内部**的规范化配置（{@link ServerCompareConfig} 的 JSON），
     * 不是面向用户的 CLI 配置，所以这里直接按类型反序列化，不走 {@code fromContent} 的形状解析；
     * 用户侧仍然只有 CLI 形状一种写法。
     */
    public ServerCompareConfig fromArtifact(String artifactId) {
        ArtifactContentDto content = artifactService.getArtifactContent(artifactId);
        if (!"CONFIG".equals(content.getArtifactType())) {
            throw new InvalidInputException("Artifact is not a CONFIG artifact: " + artifactId);
        }
        try {
            ServerCompareConfig config = objectMapper.readValue(content.getContent(),
                    ServerCompareConfig.class);
            validate(config);
            return config;
        } catch (InvalidInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidInputException("CONFIG artifact content is not a valid compare config: "
                    + exception.getMessage());
        }
    }

    public ServerCompareConfig fromContent(Object content) {
        if (content instanceof ServerCompareConfig) {
            ServerCompareConfig config = (ServerCompareConfig) content;
            validate(config);
            return config;
        }
        if (content instanceof String) {
            return parse((String) content);
        }
        // 统一形状：任务定义与 CLI 配置同构（source/target + resource、comparison.keys/fields 成对、
        // strategy），不再维护第二套"服务端形状"，也就不需要任何形状嗅探或二次转换。
        return fromCliConfig(resolveEnvironment(mapFrom(content)));
    }

    public void validate(ServerCompareConfig config) {
        if (config == null) {
            throw new InvalidInputException("config cannot be null");
        }
        if (config.getSource() == null || config.getTarget() == null) {
            throw new InvalidInputException("config.source and config.target are required");
        }
        requireEndpointResource("source", config.getSource());
        requireEndpointResource("target", config.getTarget());
        if (config.getKeys() == null || normalizedStrings(config.getKeys()).isEmpty()) {
            throw new InvalidInputException("config.keys cannot be empty");
        }
        if (config.getComparison() != null) {
            requireCompleteMappings("keyMappings", config.getComparison().getKeyMappings());
            requireCompleteMappings("fieldMappings", config.getComparison().getFieldMappings());
        }
    }

    /** 映射两端都必须有列名；空字符串会生成 `""` 形式的非法 SQL 标识符。 */
    private void requireCompleteMappings(String name, List<FieldMapping> mappings) {
        if (mappings == null) {
            return;
        }
        for (FieldMapping mapping : mappings) {
            if (mapping == null || isBlank(mapping.getSource()) || isBlank(mapping.getTarget())) {
                throw new InvalidInputException(
                        "config.comparison." + name + " contains an incomplete mapping");
            }
        }
    }

    /** File sources resolve paths locally and do not need a connection block. */
    private boolean requiresConnection(EndpointConfig endpoint) {
        return endpoint != null && !"file".equalsIgnoreCase(endpoint.getType());
    }

    public CompareRequest toCompareRequest(ServerCompareConfig config, RunRequest.Options options) {
        validate(config);
        if (requiresConnection(config.getSource())
                && (config.getSource().getConnection() == null
                || config.getSource().getConnection().isEmpty())) {
            throw new InvalidInputException("config.source.connection is required for non-dry-run execution");
        }
        if (requiresConnection(config.getTarget())
                && (config.getTarget().getConnection() == null
                || config.getTarget().getConnection().isEmpty())) {
            throw new InvalidInputException("config.target.connection is required for non-dry-run execution");
        }
        KeySpec keys = KeySpec.builder().fields(normalizedStrings(config.getKeys())).build();
        ComparisonConfig comparison = config.getComparison();
        boolean hasFieldMappings = comparison != null
                && comparison.getFieldMappings() != null && !comparison.getFieldMappings().isEmpty();
        boolean hasKeyMappings = comparison != null
                && comparison.getKeyMappings() != null && !comparison.getKeyMappings().isEmpty();

        List<String> sourceFields = hasFieldMappings
                ? mappingSources(comparison.getFieldMappings())
                : comparisonFields(comparison);
        List<String> targetFields = hasFieldMappings
                ? mappingTargets(comparison.getFieldMappings())
                : comparisonFields(comparison);
        List<String> excluded = comparison != null ? comparison.getIgnoreColumns() : null;

        ComparisonSpec sourceComparisons = ComparisonSpec.builder()
                .fields(emptyToNull(sourceFields))
                .exclude(emptyToNull(excluded))
                .build();
        ComparisonSpec targetComparisons = ComparisonSpec.builder()
                .fields(emptyToNull(targetFields))
                .exclude(emptyToNull(excluded))
                .build();

        // 主键映射：两侧主键列名不同时按映射分别指定
        KeySpec sourceKeys = hasKeyMappings
                ? KeySpec.builder().fields(emptyToNull(mappingSources(comparison.getKeyMappings()))).build()
                : keys;
        KeySpec targetKeys = hasKeyMappings
                ? KeySpec.builder().fields(emptyToNull(mappingTargets(comparison.getKeyMappings()))).build()
                : keys;

        return CompareRequest.builder()
                .source(connectorConfig("source", config.getSource()))
                .target(connectorConfig("target", config.getTarget()))
                .sourceKeySpec(sourceKeys)
                .targetKeySpec(targetKeys)
                .sourceComparisons(sourceComparisons)
                .targetComparisons(targetComparisons)
                .sourceFilter(filter(config.getSource()))
                .targetFilter(filter(config.getTarget()))
                .normalizationSpec(normalizationSpec(comparison))
                .strategyPreference(strategyPreference(config.getHints()))
                .executionOptions(executionOptions(config, options))
                .build();
    }

    /**
     * 服务端定义里的 normalization 与 CLI 走同一个编译入口，
     * 避免"同一份配置、两个入口、不同归一化结论"。
     */
    private com.consilens.connector.api.normalization.NormalizationSpec normalizationSpec(ComparisonConfig comparison) {
        if (comparison == null || comparison.getNormalization() == null) {
            return null;
        }
        NormalizationConfig normalization = comparison.getNormalization();
        com.consilens.connector.api.normalization.NormalizationSpec spec =
                com.consilens.connector.api.normalization.NormalizationSpecs.fromTypeRules(
                        ruleMaps(normalization.getGlobal()),
                        ruleMaps(normalization.getSource()),
                        ruleMaps(normalization.getTarget()));
        return com.consilens.connector.api.normalization.NormalizationSpecs.withColumnRules(
                spec, ruleMaps(normalization.getColumns()));
    }

    private Map<String, Map<String, Object>> ruleMaps(Map<String, TypeNormalizationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TypeNormalizationRule> entry : rules.entrySet()) {
            TypeNormalizationRule rule = entry.getValue();
            if (rule == null) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            putIfPresent(fields, "precision", rule.getPrecision());
            putIfPresent(fields, "rounding", rule.getRounding());
            putIfPresent(fields, "format", rule.getFormat());
            putIfPresent(fields, "timezone", rule.getTimezone());
            putIfPresent(fields, "comparisonMode", rule.getComparisonMode());
            putIfPresent(fields, "encoding", rule.getEncoding());
            putIfPresent(fields, "uppercase", rule.getUppercase());
            putIfPresent(fields, "trueValue", rule.getTrueValue());
            putIfPresent(fields, "falseValue", rule.getFalseValue());
            putIfPresent(fields, "nullValue", rule.getNullValue());
            putIfPresent(fields, "tolerance", rule.getTolerance());
            putIfPresent(fields, "relativeTolerance", rule.getRelativeTolerance());
            putIfPresent(fields, "sessionTimeZone", rule.getSessionTimeZone());
            result.put(entry.getKey(), fields);
        }
        return result;
    }

    private List<String> comparisonFields(ComparisonConfig comparison) {
        if (comparison == null) {
            return null;
        }
        return comparison.getFields() == null || comparison.getFields().isEmpty()
                ? null
                : comparison.getFields();
    }

    private List<String> mappingSources(List<FieldMapping> mappings) {
        return mappings.stream().map(FieldMapping::getSource).collect(Collectors.toList());
    }

    private List<String> mappingTargets(List<FieldMapping> mappings) {
        return mappings.stream().map(FieldMapping::getTarget).collect(Collectors.toList());
    }

    public Map<String, Object> validateContent(ServerCompareConfig config) {
        List<String> messages = new ArrayList<>();
        validate(config);
        messages.add("source resource resolved as " + resourceLabel(config.getSource()));
        messages.add("target resource resolved as " + resourceLabel(config.getTarget()));
        if (config.getSource().getConnection() == null || config.getSource().getConnection().isEmpty()) {
            messages.add("source connection is absent; run must use dryRun=true or provide connection details");
        }
        if (config.getTarget().getConnection() == null || config.getTarget().getConnection().isEmpty()) {
            messages.add("target connection is absent; run must use dryRun=true or provide connection details");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true);
        result.put("messages", messages);
        result.put("configVersion", config.getVersion());
        return result;
    }

    private ServerCompareConfig parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidInputException("configContent cannot be blank");
        }
        Map<String, Object> raw;
        try {
            raw = yamlMapper.readValue(content, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new InvalidInputException("configContent is not valid YAML or JSON");
        }
        if (raw == null) {
            // 只有注释或字面量 null 的文档会被解析成 null：这是非法输入，不是 500
            throw new InvalidInputException("configContent does not contain a YAML/JSON object");
        }

        // 只有一种形状：CLI 形状（含 datasourceId 的服务端扩展），不再做形状嗅探。
        return fromCliConfig(resolveEnvironment(raw));
    }

    private NormalizationConfig cliNormalization(Map<String, Object> raw) {
        NormalizationConfig normalization = normalizationFrom(raw.get("normalization"));
        if (normalization == null) {
            normalization = normalizationFrom(mapFrom(raw.get("hints")).get("normalization"));
        }
        return normalization;
    }

    private NormalizationConfig normalizationFrom(Object raw) {
        if (!(raw instanceof Map) || ((Map<?, ?>) raw).isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(raw, NormalizationConfig.class);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException("normalization is not valid: " + exception.getMessage());
        }
    }

    private ServerCompareConfig fromCliConfig(Map<String, Object> raw) {
        Map<String, Object> sourceMap = mapFrom(raw.get("source"));
        Map<String, Object> targetMap = mapFrom(raw.get("target"));
        if (sourceMap.isEmpty() || targetMap.isEmpty()) {
            throw new InvalidInputException("config.source and config.target are required");
        }
        Map<String, Object> comparison = mapFrom(raw.get("comparison"));
        Map<String, Object> strategy = mapFrom(raw.get("strategy"));

        // 保留 CLI 的完整执行参数：mode/localCompare/maxDifferences 等此前被丢弃，
        // 服务端任务会悄悄回落到默认 checksum+full，与 CLI 本地运行结论/性能不一致。
        Map<String, Object> executionOptions = new LinkedHashMap<>(mapFrom(raw.get("executionOptions")));
        putIfPresent(executionOptions, "bisectionFactor", strategy.get("bisectionFactor"));
        putIfPresent(executionOptions, "bisectionThreshold", strategy.get("bisectionThreshold"));
        putIfPresent(executionOptions, "checksumAlgorithm", strategy.get("algorithm"));
        putIfPresent(executionOptions, "validateUniqueKeys", strategy.get("validateUniqueKeys"));
        putIfPresent(executionOptions, "allowNullableKeys", strategy.get("allowNullableKeys"));
        putIfPresent(executionOptions, "maxDifferences", strategy.get("maxDifferences"));
        // 预算三个键同样在 CLI 的 strategy 块里，漏搬等于"设了预算但服务端不执行"
        putIfPresent(executionOptions, "maxDurationMs", strategy.get("maxDurationMs"));
        putIfPresent(executionOptions, "maxRowsScanned", strategy.get("maxRowsScanned"));
        putIfPresent(executionOptions, "maxBytesFetched", strategy.get("maxBytesFetched"));
        putIfPresent(executionOptions, "localCompareMode", mapFrom(strategy.get("localCompare")).get("mode"));

        Map<String, Object> keysMap = mapFrom(comparison.get("keys"));
        List<String> sourceKeys = listFrom(keysMap.get("source"));
        List<String> targetKeys = listFrom(keysMap.get("target"));
        List<String> keys = !sourceKeys.isEmpty() ? sourceKeys : targetKeys;

        Map<String, Object> fieldsMap = mapFrom(comparison.get("fields"));
        List<String> sourceFields = listFrom(fieldsMap.get("source"));
        List<String> targetFields = listFrom(fieldsMap.get("target"));

        // 两侧列名/键名不同时必须转成显式映射：拼成一个并集列表会按位置错配，
        // 把 source 的 A 列与 target 的 B 列放在一起比较。
        List<FieldMapping> fieldMappings = new ArrayList<>(pairMappings("comparison.fields", sourceFields, targetFields));
        fieldMappings.addAll(parseMappings(comparison.get("mappings"), "comparison.mappings"));
        List<FieldMapping> keyMappings = pairMappings("comparison.keys", sourceKeys, targetKeys);

        List<String> fields = new ArrayList<>(sourceFields);
        if (fields.isEmpty() && !targetFields.isEmpty() && fieldMappings.isEmpty()) {
            fields.addAll(targetFields);
        } else if (!sourceFields.isEmpty() && !targetFields.isEmpty()
                && !sourceFields.equals(targetFields)) {
            // 差异由 fieldMappings 表达
            fields.clear();
        }

        Map<String, Object> hints = new LinkedHashMap<>();
        Map<String, Object> strategyPreference = strategyPreferenceFromStrategy(strategy);
        if (strategyPreference != null) {
            hints.put("strategyPreference", strategyPreference);
        }

        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpointFromCli("source", sourceMap, comparison))
                .target(endpointFromCli("target", targetMap, comparison))
                .keys(keys)
                .comparison(ComparisonConfig.builder()
                        .fields(fields)
                        .ignoreColumns(cliIgnoreColumns(comparison))
                        .fieldMappings(fieldMappings)
                        .keyMappings(keyMappings)
                        .normalization(cliNormalization(raw))
                        .build())
                .hints(hints)
                .executionOptions(executionOptions)
                .result(resultConfig(raw.get("result")))
                .build();
        validate(config);
        return config;
    }

    /**
     * 把 CLI 的 source/target 两个列名列表配对成映射；列表相同则返回空（按同名比较）。
     */
    private List<FieldMapping> pairMappings(String path, List<String> sourceNames, List<String> targetNames) {
        if (sourceNames.isEmpty() && targetNames.isEmpty()) {
            return List.of();
        }
        if (sourceNames.size() != targetNames.size()) {
            throw new InvalidInputException(path + " 两侧数量必须一致: source=" + sourceNames
                    + ", target=" + targetNames);
        }
        if (sourceNames.equals(targetNames)) {
            return List.of();
        }
        List<FieldMapping> mappings = new ArrayList<>(sourceNames.size());
        for (int i = 0; i < sourceNames.size(); i++) {
            mappings.add(FieldMapping.builder().source(sourceNames.get(i)).target(targetNames.get(i)).build());
        }
        return mappings;
    }

    private EndpointConfig endpointFromCli(String side, Map<String, Object> endpoint, Map<String, Object> comparison) {
        Map<String, Object> resource = mapFrom(endpoint.get("resource"));
        String resourceType = string(resource.get("type"));
        String resourceName = string(resource.get("name"));
        if (isBlank(resourceName)) {
            resourceName = string(resource.get("path"));
        }
        String query = string(resource.get("query"));
        if (isBlank(query) && "sql".equals(resourceType)) {
            query = resourceName;
        }
        String table = isBlank(resourceType) || "table".equals(resourceType)
                || "file".equals(resourceType) ? resourceName : null;
        Map<String, Object> readOptions = mapFrom(endpoint.get("readOptions"));
        if ("file".equals(resourceType)) {
            // CLI file options (format, delimiter, hasHeader, ...) live on the
            // resource; expose them through ReadOptions so the file connector
            // sees the same format/delimiter as a CLI run.
            Map<String, Object> options = new LinkedHashMap<>(mapFrom(readOptions.get("options")));
            for (Map.Entry<String, Object> entry : resource.entrySet()) {
                String key = entry.getKey();
                if (!"type".equals(key) && !"name".equals(key) && !"path".equals(key)) {
                    options.putIfAbsent(key, entry.getValue());
                }
            }
            readOptions.put("options", options);
        }
        Map<String, Object> filters = mapFrom(comparison.get("filters"));
        Map<String, Object> connection = mapFrom(endpoint.get("connection"));
        // 统一形状：CLI 的 source/target + resource 结构，加上服务端的数据源引用。
        // datasourceId/database 允许写在 endpoint 上，也允许写在 connection 块里（更贴近 CLI 的 connection 语义）。
        Long datasourceId = datasourceId(endpoint.get("datasourceId") != null
                ? endpoint.get("datasourceId") : connection.get("datasourceId"));
        String database = trim(string(endpoint.get("database")));
        if (isBlank(database)) {
            database = trim(string(connection.get("database")));
        }
        return EndpointConfig.builder()
                .type(string(endpoint.get("type")))
                .table(table)
                .query(query)
                .filter(string(filters.get(side)))
                .datasourceId(datasourceId)
                .database(database)
                .connection(connection)
                .readOptions(readOptions)
                .build();
    }

    /** 解析 datasourceId：支持数字/字符串；非法值要报错，不能静默当成未配置。 */
    private Long datasourceId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            throw new InvalidInputException("datasourceId is not a number: " + text);
        }
    }

    private List<String> cliIgnoreColumns(Map<String, Object> comparison) {
        Map<String, Object> exclude = mapFrom(comparison.get("exclude"));
        List<String> result = new ArrayList<>(listFrom(exclude.get("source")));
        for (String column : listFrom(exclude.get("target"))) {
            if (!result.contains(column)) {
                result.add(column);
            }
        }
        return result;
    }

    private ResultConfig resultConfig(Object value) {
        if (value == null) {
            return new ResultConfig();
        }
        ResultConfig config;
        try {
            config = objectMapper.convertValue(value, ResultConfig.class);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException("config.result is not valid");
        }
        rejectUnsafeSinkPaths(config);
        return config;
    }

    /**
     * 服务端不接受"写到任意位置"的 sink 输出路径。
     *
     * <p>sink 的 {@code properties.path} 直接来自 API 请求，绝对路径或含 {@code ..} 的路径
     * 可以让调用方覆盖进程有权限写的任意文件（配置、密钥、其它任务的产物）。相对路径仍然允许，
     * 它落在服务进程自己的工作目录下。
     */
    private void rejectUnsafeSinkPaths(ResultConfig config) {
        if (config == null || config.getSinks() == null) {
            return;
        }
        for (com.consilens.sink.api.model.SinkConfig sink : config.getSinks()) {
            if (sink == null || sink.getProperties() == null || sink.getProperties().isBlank()) {
                continue;
            }
            String path;
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        objectMapper.readTree(sink.getProperties());
                com.fasterxml.jackson.databind.JsonNode pathNode = node.path("path");
                path = pathNode.isMissingNode() || pathNode.isNull() ? null : pathNode.asText();
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // properties 不是 JSON：由 sink 自己的解析报错，这里只兜住能解析的形态
                continue;
            }
            if (path == null || path.isBlank()) {
                continue;
            }
            String problem = unsafePathProblem(path);
            if (problem != null) {
                throw new InvalidInputException("result.sinks[].properties.path " + problem
                        + "; use a path relative to the server working directory");
            }
        }
    }

    private String unsafePathProblem(String path) {
        java.nio.file.Path resolved;
        try {
            resolved = java.nio.file.Paths.get(path);
        } catch (java.nio.file.InvalidPathException exception) {
            return "is not a valid path: " + path;
        }
        if (resolved.isAbsolute() || path.startsWith("/") || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*")) {
            return "must be relative, got: " + path;
        }
        for (java.nio.file.Path segment : resolved) {
            if ("..".equals(segment.toString())) {
                return "must not contain '..', got: " + path;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveEnvironment(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), resolveEnvironmentValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object resolveEnvironmentValue(Object value) {
        if (value instanceof Map) {
            return resolveEnvironment((Map<String, Object>) value);
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                result.add(resolveEnvironmentValue(item));
            }
            return result;
        }
        if (value instanceof String) {
            return resolveEnvironmentPlaceholders((String) value);
        }
        return value;
    }

    private String resolveEnvironmentPlaceholders(String value) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        matcher.reset();
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String replacement = System.getenv(variableName);
            if (replacement == null) {
                if (defaultValue == null) {
                    // 没默认值也不猜：留着占位符比悄悄用空串安全（下游报错能看到原样内容）
                    replacement = matcher.group(0);
                } else {
                    replacement = defaultValue;
                }
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private EndpointConfig endpoint(String side, PlanRequest.Endpoint endpoint, Map<String, Object> hints) {
        return EndpointConfig.builder()
                .type(trim(endpoint.getType()))
                .table(trim(endpoint.getTable()))
                .query(trim(endpoint.getQuery()))
                .filter(trim(firstString(hints.get(side + "Filter"), hints.get("filter"))))
                .connection(connection(side, hints))
                .readOptions(readOptions(side, hints))
                .build();
    }

    private ConnectorConfig connectorConfig(String side, EndpointConfig endpoint) {
        return ConnectorConfig.builder()
                .type(endpoint.getType())
                .name(side)
                .connection(copyMap(endpoint.getConnection()))
                .resource(resource(endpoint))
                .readOptions(readOptions(endpoint.getReadOptions()))
                .build();
    }

    private ResourceLocator resource(EndpointConfig endpoint) {
        if (endpoint.getQuery() != null && !endpoint.getQuery().isBlank()) {
            return ResourceLocator.builder()
                    .type("sql")
                    .path(endpoint.getQuery().trim())
                    .build();
        }
        return ResourceLocator.builder()
                .type("table")
                .name(endpoint.getTable().trim())
                .build();
    }

    private PredicateSpec filter(EndpointConfig endpoint) {
        if (endpoint.getFilter() == null || endpoint.getFilter().isBlank()) {
            return null;
        }
        return PredicateSpec.builder()
                .type("sql")
                .expression(endpoint.getFilter().trim())
                .build();
    }

    private ReadOptions readOptions(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return ReadOptions.builder()
                .consistency(string(raw.get("consistency")))
                .batchSize(integer(raw.get("batchSize")))
                .fetchSize(integer(raw.get("fetchSize")))
                .options(mapFrom(raw.get("options")))
                .build();
    }

    private CompareExecutionOptions executionOptions(ServerCompareConfig config, RunRequest.Options runOptions) {
        Map<String, Object> merged = copyMap(config.getExecutionOptions());
        if (runOptions != null && runOptions.getTimeoutMs() != null) {
            merged.put("timeoutMs", runOptions.getTimeoutMs());
        }
        return CompareExecutionOptions.builder()
                .bisectionFactor(integer(merged.get("bisectionFactor")))
                .bisectionThreshold(longValue(merged.get("bisectionThreshold")))
                .checksumAlgorithm(string(merged.get("checksumAlgorithm")))
                .localCompareMode(string(merged.get("localCompareMode")))
                .validateUniqueKeys(booleanValue(merged.get("validateUniqueKeys")))
                .allowNullableKeys(booleanValue(merged.get("allowNullableKeys")))
                .maxDifferences(longValue(merged.get("maxDifferences")))
                .maxDurationMs(longValue(merged.get("maxDurationMs")))
                .maxRowsScanned(longValue(merged.get("maxRowsScanned")))
                .maxBytesFetched(longValue(merged.get("maxBytesFetched")))
                .attributes(merged.isEmpty() ? null : merged)
                .build();
    }

    /**
     * CLI 的 {@code strategy.mode} → 计划偏好，口径与 CLI 的
     * {@code CompareRequestFactory#toStrategyPreference} 完全一致：
     * {@code join} 强制 {@code server-join} 且禁止回退，其余（checksum）优先
     * {@code pushdown-checksum}/{@code streaming-merge} 且允许回退。
     *
     * <p>此前这里直接拿 mode 当计划名（{@code "checksum"}）并统一 {@code allowFallback=true}，
     * 与 CLI 行为不一致：join 任务可能悄悄回退到 checksum，结论看似一样、实际测的不是目标计划。
     */
    private Map<String, Object> strategyPreferenceFromStrategy(Map<String, Object> strategy) {
        List<String> explicit = listFrom(strategy.get("preferredPlans"));
        if (!explicit.isEmpty()) {
            Object fallback = strategy.get("allowFallback");
            boolean allow = fallback instanceof Boolean && (Boolean) fallback;
            return Map.of("preferredPlans", explicit, "allowFallback", allow);
        }
        return strategyPreferenceFromMode(string(strategy.get("mode")));
    }

    private Map<String, Object> strategyPreferenceFromMode(String mode) {
        if (isBlank(mode)) {
            return null;
        }
        if ("join".equalsIgnoreCase(mode.trim())) {
            return Map.of("preferredPlans", List.of("server-join"), "allowFallback", false);
        }
        return Map.of("preferredPlans", List.of("pushdown-checksum", "streaming-merge"),
                "allowFallback", true);
    }

    private CompareStrategyPreference strategyPreference(Map<String, Object> hints) {
        if (hints == null) {
            return null;
        }
        Map<String, Object> raw = mapFrom(hints.get("strategyPreference"));
        if (raw.isEmpty()) {
            return null;
        }
        return CompareStrategyPreference.builder()
                .preferredPlans(listFrom(raw.get("preferredPlans")))
                .allowFallback(booleanValue(raw.get("allowFallback")))
                .options(mapFrom(raw.get("options")))
                .build();
    }

    private Map<String, Object> connection(String side, Map<String, Object> hints) {
        Map<String, Object> direct = mapFrom(hints.get(side + "Connection"));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        putIfPresent(prefixed, "url", hints.get(side + "Url"));
        putIfPresent(prefixed, "username", firstValue(hints.get(side + "Username"), hints.get(side + "User")));
        putIfPresent(prefixed, "password", hints.get(side + "Password"));
        putIfPresent(prefixed, "driver", firstValue(hints.get(side + "Driver"), hints.get(side + "DriverClassName")));
        return prefixed;
    }

    private Map<String, Object> readOptions(String side, Map<String, Object> hints) {
        return mapFrom(hints.get(side + "ReadOptions"));
    }

    private void requireEndpointResource(String side, PlanRequest.Endpoint endpoint) {
        if (endpoint == null) {
            throw new InvalidInputException(side + " endpoint is required");
        }
        if (isBlank(endpoint.getTable()) && isBlank(endpoint.getQuery())) {
            throw new InvalidInputException(side + ".table or " + side + ".query is required");
        }
    }

    private void requireEndpointResource(String side, EndpointConfig endpoint) {
        if (endpoint == null || isBlank(endpoint.getType())) {
            throw new InvalidInputException(side + ".type is required");
        }
        if (isBlank(endpoint.getTable()) && isBlank(endpoint.getQuery())) {
            throw new InvalidInputException(side + ".table or " + side + ".query is required");
        }
    }

    private String resourceLabel(EndpointConfig endpoint) {
        return endpoint.getQuery() != null && !endpoint.getQuery().isBlank()
                ? "sql"
                : "table:" + endpoint.getTable();
    }

    private List<String> normalizedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> listFrom(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toList());
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return List.of(((String) value).trim());
        }
        return List.of();
    }

    private Map<String, Object> mapFrom(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(objectMapper.convertValue(value, MAP_TYPE));
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private <T> List<T> emptyToNull(List<T> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !isBlankString(value)) {
            map.put(key, value);
        }
    }

    private Object firstValue(Object first, Object second) {
        return first != null ? first : second;
    }

    private String firstString(Object first, Object second) {
        String firstString = string(first);
        return firstString != null ? firstString : string(second);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isBlankString(Object value) {
        return value instanceof String && ((String) value).isBlank();
    }

    private Integer integer(Object value) {
        if (value instanceof Number) {
            long parsed = integralNumber(value, "numeric option must be an integer");
            if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                throw new InvalidInputException("numeric option must be an integer: " + value);
            }
            return (int) parsed;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new InvalidInputException("numeric option must be an integer: " + value);
            }
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return integralNumber(value, "numeric option must be a long");
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new InvalidInputException("numeric option must be a long: " + value);
            }
        }
        return null;
    }

    /** 4.5 / NaN / Infinity 不能截断成整数配置值。 */
    private long integralNumber(Object value, String message) {
        try {
            long parsed = value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    ? ((Number) value).longValue()
                    : new java.math.BigDecimal(value.toString()).longValueExact();
            return parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidInputException(message + ": " + value);
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            String normalized = ((String) value).trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return Boolean.FALSE;
            }
            throw new InvalidInputException("boolean option must be true or false: " + value);
        }
        return null;
    }
}
