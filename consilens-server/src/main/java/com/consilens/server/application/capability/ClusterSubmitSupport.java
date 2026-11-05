package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmissionContext;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 集群提交支撑：把 ServerCompareConfig 渲染为 CLI 兼容 descriptor，
 * 经 ClusterSubmitter SPI 提交到集群并等待终态。
 * 平台差异（execution mode、descriptor 引用方式、提交 spec）由各
 * ClusterSubmitter 实现负责；本类只保留通用骨架。
 */
@Component
public class ClusterSubmitSupport {

    private static final Logger log = LoggerFactory.getLogger(ClusterSubmitSupport.class);

    private static final long COMPLETION_POLL_SECONDS = 3;
    /** 与 CLI/服务端默认一致：checksum 是默认计划，join 仅在显式指定时使用。 */
    private static final String DEFAULT_STRATEGY_MODE = "checksum";

    private final ClusterSubmitterLocator submitterLocator;
    private final ConsilensServerProperties properties;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 测试用构造：未配置 loopback 替换。 */
    public ClusterSubmitSupport(ClusterSubmitterLocator submitterLocator) {
        this(submitterLocator, new ConsilensServerProperties());
    }

    @Autowired
    public ClusterSubmitSupport(ClusterSubmitterLocator submitterLocator,
                                ConsilensServerProperties properties) {
        this.submitterLocator = submitterLocator;
        this.properties = properties;
    }

    public boolean isClusterPlatform(RunRequest.Options options) {
        return options != null && options.getPlatform() != null
                && !"local".equalsIgnoreCase(options.getPlatform().trim());
    }

    /** 提交并阻塞等待终态；轮询期间检测取消请求并 kill 集群应用。 */
    public ClusterApplicationResult submitAndAwait(TaskExecutionContext context,
                                                   RunRequest request,
                                                   ServerCompareConfig config,
                                                   CancelChecker cancelChecker) throws Exception {
        String platform = normalizedPlatform(request);
        ClusterSubmitter submitter = submitterLocator.locate(platform);
        String descriptor = yamlMapper.writeValueAsString(
                buildDescriptor(config, submitter.descriptorRedactsPasswords(), platform));
        Path descriptorFile = submitter.requiresDescriptorFile() ? writeDescriptor(descriptor) : null;
        ClusterSubmission submission = null;
        boolean reachedTerminalState = false;
        try {
            // 平台提交请求（descriptor 引用、execution mode、平台 spec）由 submitter 构造
            ClusterSubmitRequest submitRequest = submitter.buildRequest(new ClusterSubmissionContext(
                    contextSubmissionId(request), descriptor, descriptorFile,
                    orEmpty(request.getOptions().getProperties())));
            submitRequest.validate();
            // 恢复重试场景：清扫同 submissionId 的残留集群应用（全新提交时为 no-op）
            try {
                submitter.killStale(submitRequest);
            } catch (RuntimeException e) {
                // 清扫失败不阻塞新提交；记录原因便于排查
                log.warn("stale cluster application sweep failed for submission {}; continuing with new submission",
                        submitRequest.getSubmissionId(), e);
            }
            submission = submitter.submit(submitRequest);
            ClusterApplicationResult result = awaitTerminal(context, request, submitter, submission, cancelChecker);
            reachedTerminalState = true;
            return result;
        } finally {
            if (submission != null && !reachedTerminalState) {
                try {
                    // 仅在提交后异常、超时或取消时终止，正常完成的 Job 不应被删除。
                    submitter.kill(submission);
                } catch (RuntimeException ignored) {
                    // 原始失败原因优先；终止为尽力而为。
                }
            }
            if (submitter instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) submitter).close();
                } catch (Exception ignored) {
                    // 提交结果已确定；清理失败不影响结果
                }
            }
            if (descriptorFile != null) {
                try {
                    Files.deleteIfExists(descriptorFile);
                } catch (Exception exception) {
                    // 临时文件清理失败不能覆盖提交结果：成功时会把 SUCCEEDED 变成异常，
                    // 失败时又会丢掉原始错误。留给 /tmp 清理即可。
                    log.warn("failed to delete cluster descriptor file {}: {}",
                            descriptorFile, exception.getMessage());
                }
            }
        }
    }

    private ClusterApplicationResult awaitTerminal(TaskExecutionContext context,
                                                   RunRequest request,
                                                   ClusterSubmitter submitter,
                                                   ClusterSubmission submission,
                                                   CancelChecker cancelChecker) throws Exception {
        Integer timeoutMs = request.getOptions().getTimeoutMs();
        long deadline = timeoutMs != null && timeoutMs > 0
                ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            if (cancelChecker.isCancellationRequested()) {
                throw new TaskCancellationException(context.getInstanceKey());
            }
            ClusterApplicationResult result = submitter.awaitCompletion(submission,
                    Duration.ofSeconds(COMPLETION_POLL_SECONDS));
            if (result != null) {
                return result;
            }
        }
        throw new java.util.concurrent.TimeoutException(
                "cluster application did not finish before timeout: " + submission.getClusterApplicationId());
    }

    private String normalizedPlatform(RunRequest request) {
        String platform = request.getOptions().getPlatform();
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("cluster platform is required");
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private Path writeDescriptor(String descriptor) throws Exception {
        Path descriptorFile = Files.createTempFile("consilens-descriptor-", ".yaml");
        Files.writeString(descriptorFile, descriptor);
        return descriptorFile;
    }

    /** ServerCompareConfig → CLI 兼容 descriptor（与 examples/*.yaml 同构）。 */
    Map<String, Object> buildDescriptor(ServerCompareConfig config, boolean redactPasswords) {
        return buildDescriptor(config, redactPasswords, null);
    }

    Map<String, Object> buildDescriptor(ServerCompareConfig config, boolean redactPasswords, String platform) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("source", endpointDescriptor(config.getSource(), "source", redactPasswords, platform));
        descriptor.put("target", endpointDescriptor(config.getTarget(), "target", redactPasswords, platform));

        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("source", keyColumns(config, true));
        keys.put("target", keyColumns(config, false));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("source", compareColumns(config, true));
        fields.put("target", compareColumns(config, false));
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("keys", keys);
        comparison.put("fields", fields);
        // 忽略列必须随 descriptor 下发，否则集群侧会把用户明确排除的列也纳入比较，
        // 产生一份与本地执行不同的差异结果。
        java.util.List<String> exclude = config.getComparison() != null
                && config.getComparison().getIgnoreColumns() != null
                ? config.getComparison().getIgnoreColumns() : java.util.List.of();
        if (!exclude.isEmpty()) {
            Map<String, Object> excludePair = new LinkedHashMap<>();
            excludePair.put("source", exclude);
            excludePair.put("target", exclude);
            comparison.put("exclude", excludePair);
        }
        // CLI 的过滤条件在 comparison.filters（且要求两端同时配置），
        // 不能放在 endpoint 顶层，否则集群侧反序列化直接失败。
        String sourceFilter = config.getSource() != null ? config.getSource().getFilter() : null;
        String targetFilter = config.getTarget() != null ? config.getTarget().getFilter() : null;
        boolean hasSourceFilter = sourceFilter != null && !sourceFilter.isBlank();
        boolean hasTargetFilter = targetFilter != null && !targetFilter.isBlank();
        if (hasSourceFilter != hasTargetFilter) {
            throw new IllegalArgumentException(
                    "cluster submission requires comparison filters on both source and target");
        }
        if (hasSourceFilter) {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("source", sourceFilter);
            filters.put("target", targetFilter);
            comparison.put("filters", filters);
        }
        descriptor.put("comparison", comparison);

        Map<String, Object> execution = config.getExecutionOptions();
        Map<String, Object> strategy = new LinkedHashMap<>();
        // 默认 checksum（与服务端/CLI 一致）；显式 executionOptions.mode 或
        // hints.strategyPreference.preferredPlans 可覆盖（join / bisection）。
        Object mode = preferredMode(config, execution);
        strategy.put("mode", mode == null || String.valueOf(mode).isBlank() ? DEFAULT_STRATEGY_MODE : mode);
        copyIfPresent(execution, "bisectionFactor", strategy);
        copyIfPresent(execution, "bisectionThreshold", strategy);
        copyIfPresent(execution, "batchSize", strategy);
        Object algorithm = execution.get("checksumAlgorithm");
        if (algorithm != null) {
            strategy.put("algorithm", algorithm);
        }
        Object localCompareMode = execution.get("localCompareMode");
        if (localCompareMode != null) {
            strategy.put("localCompare", Map.of("mode", localCompareMode));
        }
        copyIfPresent(execution, "validateUniqueKeys", strategy);
        copyIfPresent(execution, "allowNullableKeys", strategy);
        copyIfPresent(execution, "maxDifferences", strategy);
        descriptor.put("strategy", strategy);

        if (config.getResult() != null) {
            // descriptor 会被 K8s ConfigMap / 挂载文件持久化：表 sink 的密码必须走
            // secretEnv 注入（${env.SINK_PASSWORD}），不能明文写进描述符。
            descriptor.put("result", redactPasswords
                    ? redactedResult(config.getResult())
                    : config.getResult());
        }
        return descriptor;
    }

    /**
     * 用户显式选择的计划优先于执行参数默认值：CLI 形态把 mode 映射进
     * hints.strategyPreference.preferredPlans，旧代码只读 executionOptions.mode，
     * 于是服务端提交永远回落到固定默认值，与用户配置不符。
     */
    private Object preferredMode(ServerCompareConfig config, Map<String, Object> execution) {
        Object explicit = execution.get("mode");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return explicit;
        }
        Object preference = config.getHints() != null ? config.getHints().get("strategyPreference") : null;
        if (preference instanceof Map) {
            Object plans = ((Map<?, ?>) preference).get("preferredPlans");
            if (plans instanceof java.util.List && !((java.util.List<?>) plans).isEmpty()) {
                Object first = ((java.util.List<?>) plans).get(0);
                if (first != null && !String.valueOf(first).isBlank()) {
                    return first;
                }
            }
        }
        return null;
    }

    /** 复制一份 result 配置，把每个 sink 的 password 换成 ${env.SINK_PASSWORD}。 */
    private com.consilens.sink.api.model.ResultConfig redactedResult(
            com.consilens.sink.api.model.ResultConfig result) {
        if (result.getSinks() == null || result.getSinks().isEmpty()) {
            return result;
        }
        List<com.consilens.sink.api.model.SinkConfig> sinks = new ArrayList<>(result.getSinks().size());
        for (com.consilens.sink.api.model.SinkConfig sink : result.getSinks()) {
            if (sink == null) {
                continue;
            }
            sinks.add(com.consilens.sink.api.model.SinkConfig.builder()
                    .format(sink.getFormat())
                    .type(sink.getType())
                    .enabled(sink.isEnabled())
                    .properties(redactSinkProperties(sink.getProperties()))
                    .build());
        }
        return com.consilens.sink.api.model.ResultConfig.builder()
                .sinks(sinks)
                .failOnSinkError(result.isFailOnSinkError())
                .build();
    }

    /**
     * sink properties 是 JSON 串：递归把 password 字段替换成 ${env.SINK_PASSWORD}。
     * 解析失败时直接丢弃属性，宁可让集群侧用默认值，也不能把可能的密码写进 ConfigMap。
     */
    private String redactSinkProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            return properties;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(properties);
            redactPasswordFields(node);
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception exception) {
            log.warn("Unable to inspect sink properties for credential redaction; dropping them",
                    exception);
            return "{}";
        }
    }

    private void redactPasswordFields(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
            com.fasterxml.jackson.databind.node.ObjectNode object =
                    (com.fasterxml.jackson.databind.node.ObjectNode) node;
            // 只替换值、不改结构：边遍历边 remove 容易踩到 ObjectNode 的字段顺序语义
            List<String> credentials = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if ("password".equalsIgnoreCase(name)) {
                    credentials.add(name);
                }
            });
            for (String name : credentials) {
                object.put(name, "${env.SINK_PASSWORD}");
            }
            object.fields().forEachRemaining(entry -> {
                if (!credentials.contains(entry.getKey())) {
                    redactPasswordFields(entry.getValue());
                }
            });
        } else if (node != null && node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                redactPasswordFields(child);
            }
        }
    }

    private Map<String, Object> endpointDescriptor(com.consilens.server.application.capability.config.EndpointConfig endpoint,
                                                   String name,
                                                   boolean redactPasswords,
                                                   String platform) {
        if (endpoint == null || endpoint.getConnection() == null || endpoint.getConnection().isEmpty()) {
            throw new IllegalArgumentException("endpoint connection is required for cluster submission: " + name);
        }
        Map<String, Object> connection = new LinkedHashMap<>(endpoint.getConnection());
        rewriteLoopbackHost(connection, platform);
        if (redactPasswords && connection.get("password") != null) {
            connection.put("password", "${env." + name.toUpperCase(Locale.ROOT) + "_PASSWORD}");
        }
        boolean query = endpoint.getQuery() != null && !endpoint.getQuery().isBlank();
        Map<String, Object> resource = new LinkedHashMap<>();
        // CLI 约定：table 用 name，sql 用 path（type=sql 时 name 不允许）
        resource.put("type", query ? "sql" : "table");
        if (query) {
            resource.put("path", endpoint.getQuery());
        } else {
            resource.put("name", endpoint.getTable());
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", endpoint.getType());
        section.put("name", name + "-" + endpoint.getType());
        section.put("connection", connection);
        section.put("resource", resource);
        // CLI 的 readOptions 是 endpoint 级字段（与 connection 平级），
        // 放进 connection 会被 @JsonAnySetter 当成 JDBC 属性吞掉，读取参数静默失效。
        if (endpoint.getReadOptions() != null && !endpoint.getReadOptions().isEmpty()) {
            section.put("readOptions", new LinkedHashMap<>(endpoint.getReadOptions()));
        }
        return section;
    }

    /**
     * 本机开发环境：业务数据源统一写 127.0.0.1（宿主机可直连），但 YARN AM / K8s Pod
     * 需要集群内可达的地址。提交 descriptor 时按平台把 loopback host/port 换成配置值
     * （同时替换 host/port 字段与 JDBC URL），无需为每个平台单独建数据源。
     */
    private void rewriteLoopbackHost(Map<String, Object> connection, String platform) {
        if (platform == null || platform.isBlank()) {
            return;
        }
        String replacement = properties.getCluster().getLoopbackHosts().get(platform);
        Integer replacementPort = properties.getCluster().getLoopbackPorts().get(platform);
        if ((replacement == null || replacement.isBlank()) && replacementPort == null) {
            return;
        }
        Object host = connection.get("host");
        Object url = connection.get("url");
        boolean hostLoopback = host != null && isLoopbackHost(String.valueOf(host));
        boolean urlLoopback = url != null && isLoopbackHost(jdbcHost(String.valueOf(url)));
        if (!hostLoopback && !urlLoopback) {
            return;
        }
        boolean hasPortField = connection.get("port") != null;
        int originalPort = intValue(connection.get("port"));
        // 只写 url 的连接没有 port 字段：端口必须从 URL 里取，否则 host 改了、端口没改，
        // 集群侧拿到的是半改写的 JDBC URL（连到集群内的宿主机端口）。
        if (originalPort <= 0 && url != null) {
            originalPort = jdbcPort(String.valueOf(url));
        }
        if (hostLoopback && replacement != null && !replacement.isBlank()) {
            connection.put("host", replacement);
        }
        if (replacementPort != null && hasPortField) {
            connection.put("port", replacementPort);
        }
        if (url != null) {
            String rewritten = String.valueOf(url);
            // 只替换 JDBC URL 的 host 段：裸字符串替换会改坏 query 参数/密码里出现的
            // "localhost"（例如 ?socketFactory=...localhost...）。
            if (replacement != null && !replacement.isBlank()) {
                rewritten = rewriteLoopbackInUrl(rewritten, replacement);
            }
            if (replacementPort != null && originalPort > 0 && originalPort != replacementPort) {
                rewritten = rewriteJdbcPort(rewritten, originalPort, replacementPort);
            }
            connection.put("url", rewritten);
        }
    }

    private static final java.util.regex.Pattern JDBC_AUTHORITY = java.util.regex.Pattern
            .compile("(?i)^(jdbc:[^:]+:(?://)?(?:[^@/]*@)?)(\\[[^\\]]+\\]|[^/:?;]+)(.*)$");

    private String rewriteLoopbackInUrl(String url, String replacement) {
        java.util.regex.Matcher matcher = JDBC_AUTHORITY.matcher(url);
        if (!matcher.matches()) {
            return url;
        }
        if (!isLoopbackHost(matcher.group(2).replace("[", "").replace("]", ""))) {
            return url;
        }
        return matcher.group(1) + replacement + matcher.group(3);
    }

    /** 只替换 JDBC authority 里的端口，不能误改 query 参数或密码中相同数字。 */
    private String rewriteJdbcPort(String url, int originalPort, int replacementPort) {
        java.util.regex.Matcher matcher = JDBC_AUTHORITY.matcher(url);
        if (!matcher.matches()) {
            return url;
        }
        String tail = matcher.group(3);
        String prefix = ":" + originalPort;
        if (!tail.startsWith(prefix)) {
            return url;
        }
        return matcher.group(1) + matcher.group(2) + ":" + replacementPort
                + tail.substring(prefix.length());
    }

    /** 提取 JDBC URL authority 里的端口；没有端口或非 JDBC 形态返回 0。 */
    private static int jdbcPort(String url) {
        java.util.regex.Matcher matcher = JDBC_AUTHORITY.matcher(url);
        if (!matcher.matches()) {
            return 0;
        }
        java.util.regex.Matcher port = java.util.regex.Pattern.compile("^:(\\d+)")
                .matcher(matcher.group(3));
        if (!port.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(port.group(1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 提取 JDBC URL 的 host 段；非 JDBC 形态返回 null。 */
    private String jdbcHost(String url) {
        java.util.regex.Matcher matcher = JDBC_AUTHORITY.matcher(url);
        return matcher.matches() ? matcher.group(2).replace("[", "").replace("]", "") : null;
    }

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? -1 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(normalized) || "localhost".equals(normalized) || "::1".equals(normalized);
    }

    private List<String> compareColumns(ServerCompareConfig config, boolean source) {
        com.consilens.server.application.capability.config.ComparisonConfig comparison = config.getComparison();
        if (comparison != null && comparison.getFieldMappings() != null && !comparison.getFieldMappings().isEmpty()) {
            return comparison.getFieldMappings().stream()
                    .map(m -> String.valueOf(source ? m.getSource() : m.getTarget()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return normalized(comparison == null ? null : comparison.getFields());
    }

    private List<String> keyColumns(ServerCompareConfig config, boolean source) {
        com.consilens.server.application.capability.config.ComparisonConfig comparison = config.getComparison();
        if (comparison != null && comparison.getKeyMappings() != null && !comparison.getKeyMappings().isEmpty()) {
            return comparison.getKeyMappings().stream()
                    .map(m -> String.valueOf(source ? m.getSource() : m.getTarget()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return normalized(config.getKeys());
    }

    private List<String> normalized(List<?> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            values.forEach(v -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    result.add(String.valueOf(v));
                }
            });
        }
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, String key, Map<String, Object> target) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String contextSubmissionId(RunRequest request) {
        return request.getSerialNo().replaceAll("[^A-Za-z0-9_-]", "-");
    }

    /** 取消检测：RunTaskHandler 传入 taskRepository 轮询逻辑。 */
    public interface CancelChecker {
        boolean isCancellationRequested();
    }
}
