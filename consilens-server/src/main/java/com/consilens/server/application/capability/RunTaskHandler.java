package com.consilens.server.application.capability;

import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.capability.config.SinkSecretSupport;
import com.consilens.server.application.connection.DatasourceConnectionPolicy;
import com.consilens.server.application.connection.JdbcConnectionSupport;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.consilens.server.application.datasource.DialectSupport;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.log.RunLogStore;
import com.consilens.server.support.crypto.SecretProtector;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.diff.DiffEvidenceFactory;
import com.consilens.core.diff.RunFingerprintFactory;
import com.consilens.core.lifecycle.NoopDiffLifecycle;
import com.consilens.sdk.EngineInfo;
import com.consilens.sdk.result.ColumnStatsCalculator;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.consilens.sink.api.DefaultDiffLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class RunTaskHandler implements CapabilityHandler<RunRequest> {

    /** 内联差异样本行数上限，来自 consilens.server.artifact.diff-sample-rows（默认 1000）。 */
    private final int diffSampleRows;

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;
    private final TaskRepository taskRepository;
    private final DataSourceRepository dataSourceRepository;
    private final SecretProtector secretProtector;
    private final DialectSupport dialectSupport;
    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;
    private final ClusterSubmitSupport clusterSubmitSupport;
    private final RunLogStore runLogStore;

    public RunTaskHandler(ArtifactService artifactService,
                          ServerCompareConfigService configService,
                          TaskRepository taskRepository,
                          DataSourceRepository dataSourceRepository,
                          SecretProtector secretProtector,
                          DialectSupport dialectSupport,
                          ConsilensServerProperties properties,
                          ObjectMapper objectMapper,
                          ClusterSubmitSupport clusterSubmitSupport,
                          RunLogStore runLogStore) {
        this.artifactService = artifactService;
        this.configService = configService;
        this.taskRepository = taskRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.secretProtector = secretProtector;
        this.dialectSupport = dialectSupport;
        this.properties = properties;
        this.diffSampleRows = Math.max(1, properties.getArtifact().getDiffSampleRows());
        this.objectMapper = objectMapper;
        this.clusterSubmitSupport = clusterSubmitSupport;
        this.runLogStore = runLogStore;
    }

    /**
     * 执行端连接注入：定义运行/直传配置通过 source/target.datasourceId 引用数据源，
     * 此处按需读取并解密连接参数（提交落库的 payload 不含真实密码）。
     */
    void injectConnections(ServerCompareConfig config) {
        injectEndpoint(config.getSource());
        injectEndpoint(config.getTarget());
    }

    /** CONFIG artifact 中表 sink 的密码是 v2: 密文；执行前还原，sink 才能连库。 */
    void injectSinkSecrets(ServerCompareConfig config) {
        if (config.getResult() == null || config.getResult().getSinks() == null) {
            return;
        }
        SinkSecretSupport.transformSinkConfigs(config.getResult().getSinks(),
                password -> password != null && password.startsWith("v2:")
                        ? secretProtector.reveal(password) : password);
    }

    void injectEndpoint(EndpointConfig endpoint) {
        if (endpoint == null) {
            return;
        }
        Map<String, Object> connection = endpoint.getConnection();
        // 直传配置可能把 JDBC 属性写成 connection.properties=key=value&...；
        // 执行端需要展开成独立键，JdbcDatasetHandle 才会作为驱动属性传入。
        if (connection != null && connection.get("properties") instanceof String) {
            Map<String, Object> normalized = new LinkedHashMap<>(connection);
            Object rawProperties = normalized.remove("properties");
            mergeJdbcProperties(rawProperties, normalized);
            endpoint.setConnection(normalized);
            connection = normalized;
        }
        // 情况1：提交端加密存储的密码（v2 前缀）→ 解密还原（外部直传场景）
        if (connection != null && connection.get("password") instanceof String
                && ((String) connection.get("password")).startsWith("v2:")) {
            Map<String, Object> fixed = new LinkedHashMap<>(connection);
            fixed.put("password", secretProtector.reveal((String) connection.get("password")));
            endpoint.setConnection(fixed);
            return;
        }
        // 情况2：引用数据源且 connection 里没有 url → 注入数据源的连接参数。
        // CLI 同构配置的 connection 可能只写了 datasourceId/database（非空但没有 url），
        // 旧判断 `connection.isEmpty()` 会把这种写法漏掉，最终报 "JDBC connector requires connection.url"。
        boolean hasUrl = connection != null && connection.get("url") instanceof String
                && !((String) connection.get("url")).isBlank();
        boolean needsInjection = endpoint.getDatasourceId() != null && !hasUrl;
        if (!needsInjection) {
            return;
        }
        dataSourceRepository.findById(endpoint.getDatasourceId()).ifPresentOrElse(ds -> {
            Map<String, Object> param = fromParamJson(ds.getParamJson());
            Map<String, Object> injected = new LinkedHashMap<>();
            String host = stringValue(param.get("host"));
            Integer port = integerValue(param.get("port"));
            // 与 CLI 配置一致：显式 database（向导保存）优先，其次数据源 param
            String database = endpoint.getDatabase() != null && !endpoint.getDatabase().isBlank()
                    ? endpoint.getDatabase()
                    : stringValue(param.get("database"));
            // database 直接拼入 JDBC URL，复用创建数据源时的名称白名单防 URL 参数注入
            if (database != null && !database.matches(DatasourceConnectionPolicy.NAME_CHARS_PATTERN)) {
                throw new IllegalArgumentException("database contains invalid characters");
            }
            // connector 执行端需要完整 JDBC URL，按数据源类型经 dialect 构建
            com.consilens.connector.api.DatabaseDialect dialect = dialectSupport.find(ds.getType())
                    .orElseThrow(() -> new InvalidInputException(
                            "unsupported datasource type: " + ds.getType()));
            Map<String, Object> urlParams = new LinkedHashMap<>();
            urlParams.put("host", host == null ? "" : host);
            urlParams.put("port", port == null ? dialect.getDefaultPort() : port);
            urlParams.put("database", database == null ? "" : database);
            // 方言扩展参数（Oracle sid / schema / properties）透传，不覆盖已有键
            for (String key : DatasourceConnectionPolicy.OPTION_WHITELIST) {
                Object value = param.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    urlParams.putIfAbsent(key, value);
                }
            }
            injected.put("url", dialect.buildJdbcUrl(urlParams));
            injected.put("host", host);
            injected.put("port", port);
            injected.put("database", database);
            injected.put("username", stringValue(param.get("username")));
            injected.put("password", secretProtector.reveal(
                    param.get("password") != null ? String.valueOf(param.get("password")) : null));
            mergeJdbcProperties(param.get("properties"), injected);
            endpoint.setConnection(injected);
        }, () -> {
            throw new ResourceNotFoundException(
                    "datasource not found: " + endpoint.getDatasourceId());
        });
    }

    /**
     * datasource.param.properties 是 key=value&key2=value2 形态；展开为连接 map 的独立键，
     * 由 JdbcDatasetHandle 作为驱动属性注入。敏感键/URL 组成键不允许覆盖。
     */
    private static void mergeJdbcProperties(Object raw, Map<String, Object> target) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw);
        for (String pair : text.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (key.isEmpty() || value.isEmpty()
                    || DatasourceConnectionPolicy.isSensitivePropertyKey(key)
                    || "url".equalsIgnoreCase(key) || "host".equalsIgnoreCase(key)
                    || "port".equalsIgnoreCase(key) || "database".equalsIgnoreCase(key)
                    || "username".equalsIgnoreCase(key)) {
                continue;
            }
            target.putIfAbsent(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<String, Object> fromParamJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.RUN;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, RunRequest request) {
        Instant startedAt = Instant.now();
        // MDC 生命周期由 RunTaskExecuteManager 管理（含失败/取消路径）；
        // 这里失败时先落一条失败日志再抛出，保证实例日志能看到失败原因
        try {
            ServerCompareConfig config = configService.fromRunRequest(request);
            injectConnections(config);
            injectSinkSecrets(config);
            if (Boolean.TRUE.equals(options(request).getDryRun())) {
                return writeSuccess(context, request, startedAt, true,
                        configService.validateContent(config), null);
            }
            if (clusterSubmitSupport.isClusterPlatform(options(request))) {
                return executeOnCluster(context, request, config, startedAt);
            }
            CompareOutcome outcome = executeComparison(context, config, options(request));
            assertNotCancellationRequested(context);
            return writeSuccess(context, request, startedAt, false, runResult(outcome.diffResult), outcome);
        } catch (TaskCancellationException exception) {
            log.info("run cancelled: instanceKey={}, reason={}", context.getInstanceKey(), exception.getMessage());
            throw exception;
        } catch (com.consilens.core.exception.BudgetExceededException exception) {
            String safeMessage = JdbcConnectionSupport.maskMessage(exception.getMessage());
            log.error("run failed: instanceKey={}, errorCode={}, reason={}",
                    context.getInstanceKey(), "BUDGET_EXCEEDED", safeMessage, exception);
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "BUDGET_EXCEEDED", safeMessage);
            throw new CapabilityExecutionException("BUDGET_EXCEEDED", safeMessage, artifact.getId(), false, exception);
        } catch (InvalidInputException exception) {
            String safeMessage = JdbcConnectionSupport.maskMessage(exception.getMessage());
            log.error("run failed: instanceKey={}, errorCode={}, reason={}",
                    context.getInstanceKey(), "INVALID_INPUT", safeMessage, exception);
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "INVALID_INPUT", safeMessage);
            throw new CapabilityExecutionException("INVALID_INPUT", safeMessage, artifact.getId(), false, exception);
        } catch (CapabilityExecutionException exception) {
            // 子流程已写入带正确 errorCode/retryable 的失败 artifact（如集群终态失败），
            // 不能再次包装成可重试的 RUN_EXECUTION_ERROR，否则会重复提交确定性失败的任务。
            String safeMessage = JdbcConnectionSupport.maskMessage(exception.getMessage());
            if (safeMessage != null && !safeMessage.equals(exception.getMessage())) {
                throw new CapabilityExecutionException(exception.getErrorCode(), safeMessage,
                        exception.getArtifactId(), exception.isRetryable(), exception);
            }
            throw exception;
        } catch (Exception exception) {
            String safeMessage = JdbcConnectionSupport.maskMessage(
                    withRootCauseMessage(exception, ERROR_MESSAGE_MAX_LENGTH));
            log.error("run failed: instanceKey={}, errorCode={}, reason={}",
                    context.getInstanceKey(), "RUN_EXECUTION_ERROR", safeMessage, exception);
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "RUN_EXECUTION_ERROR", safeMessage);
            throw new CapabilityExecutionException("RUN_EXECUTION_ERROR", safeMessage, artifact.getId(),
                    isRetryable(exception), exception);
        } finally {
            log.info("run finished: instanceKey={}, elapsedMs={}",
                    context.getInstanceKey(), Duration.between(startedAt, Instant.now()).toMillis());
        }
    }

    /**
     * 执行失败信息里保留根因。
     *
     * <p>引擎在比对失败时会把底层异常包装成 {@code RuntimeException("Table diff failed")} 这类
     * 只描述“哪一步失败”的信息，真正可操作的提示（例如
     * {@code Key column ... is nullable ... allowNullableKeys=true}）只存在于 cause 链里，
     * 调用方/负向探针只能拿到外层文案，排障必须翻服务端日志。
     *
     * <p>这里把最深层 cause 的 message 追加到外层 message 之后（去重、限长），保持原有前缀不变。
     */
    static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    static String withRootCauseMessage(Throwable exception, int maxLength) {
        if (exception == null) {
            return null;
        }
        String outer = exception.getMessage();
        StringBuilder text = new StringBuilder(outer == null ? "" : outer);

        String deepest = null;
        Set<String> visited = new HashSet<>();
        Throwable current = exception;
        while (current != null) {
            String key = current.getClass().getName() + "#" + current.getMessage();
            if (!visited.add(key)) {
                break;
            }
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepest = message;
            }
            current = current.getCause();
        }
        if (deepest != null && (outer == null || !outer.contains(deepest))) {
            if (text.length() > 0) {
                text.append(": ");
            }
            text.append(deepest);
        }

        String result = text.toString();
        return result.length() <= maxLength ? result : result.substring(0, maxLength) + "...";
    }

    /** 集群提交执行：descriptor 渲染 + 提交/等待/取消，摘要入执行详情 */
    private TaskExecutionResult executeOnCluster(TaskExecutionContext context,
                                                 RunRequest request,
                                                 ServerCompareConfig config,
                                                 Instant startedAt) throws Exception {
        ClusterApplicationResult result = clusterSubmitSupport.submitAndAwait(context, request, config,
                () -> taskRepository.findById(context.getTaskId())
                        .map(task -> task.getStatus() == TaskStatus.CANCEL_REQUESTED)
                        .orElse(false));
        boolean succeeded = result.succeeded();
        String safeDiagnostics = JdbcConnectionSupport.maskMessage(result.getDiagnostics());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clusterApplicationId", result.getApplicationId());
        details.put("finalStatus", result.getFinalStatus());
        if (result.getTrackingUrl() != null) {
            details.put("trackingUrl", result.getTrackingUrl());
        }
        if (result.getDiagnostics() != null) {
            details.put("diagnostics", safeDiagnostics);
        }
        if (succeeded) {
            Map<String, Object> statistics = parseClusterStatistics(result.getDiagnostics());
            if (!statistics.isEmpty()) {
                // AM 把 coordinator 的 {"submissionId","statistics"} 摘要写进 RM diagnostics，
                // 服务端据此还原统计；差异明细仍只在集群容器日志里。
                details.put("statisticsSource", "clusterDiagnostics");
                details.put("differenceDetailsAvailable", false);
                return writeSuccess(context, request, startedAt, false, details,
                        new CompareOutcome(null, 0, false, 0, statistics));
            }
            // 拿不到统计（旧 AM / K8s 未回传 / diagnostics 被截断）时不能当成"零差异"：
            // 显式标注不可用，避免报告页显示 100 分。
            details.put("statisticsUnavailable", true);
            details.put("statisticsUnavailableReason",
                    "cluster coordinator summary is not transferred back; only finalStatus is available");
            log.warn("cluster comparison {} succeeded but no statistics were returned; "
                    + "the run result will not report a match score", result.getApplicationId());
            return writeSuccess(context, request, startedAt, false, details, null);
        }
        // 失败时也要带上集群应用 ID 与 diagnostics，否则页面只有 "finished with FAILED"，
        // 无法判断是哪个应用、什么原因失败
        String reason = "cluster application " + result.getApplicationId()
                + " finished with " + result.getFinalStatus()
                + (safeDiagnostics != null ? ": " + safeDiagnostics : "");
        ArtifactRefDto artifact = writeFailure(context, request, startedAt,
                "CLUSTER_EXECUTION_FAILED", reason, details);
        throw new CapabilityExecutionException("CLUSTER_EXECUTION_FAILED", reason, artifact.getId(), false, null);
    }

    /**
     * 判断"再来一次会不会不一样"。
     *
     * <p>之前所有非预算/非入参异常都被标成可重试，于是 SQL 语法错、约束冲突、缺表这类确定性失败
     * 会被反复重试——集群上重跑一遍、日志里再记一遍、结果仍然一样。只有连接/超时这类瞬时错误
     * 才值得重试。
     */
    static boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLException) {
                String state = ((java.sql.SQLException) current).getSQLState();
                if (state != null && state.length() >= 2) {
                    String sqlClass = state.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
                    // 22 数据异常 / 23 约束冲突 / 42 语法或权限 / 0A 不支持的特性：重试结论不变
                    if (sqlClass.equals("22") || sqlClass.equals("23") || sqlClass.equals("42")
                            || sqlClass.equals("0A")) {
                        return false;
                    }
                }
            }
            if (current instanceof IllegalArgumentException
                    || current instanceof UnsupportedOperationException
                    || current instanceof java.nio.file.NoSuchFileException
                    || current instanceof java.nio.file.AccessDeniedException) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    /** 执行结果 + 差异 sink 写入状态（行数/截断） */
    static final class CompareOutcome {
        final DiffResult diffResult;
        final long writtenRecordCount;
        final boolean truncated;
        final int sinkFailureCount;
        /** 只有统计、没有 DiffResult 时（集群摘要回传）使用。 */
        final Map<String, Object> statistics;

        CompareOutcome(DiffResult diffResult, long writtenRecordCount, boolean truncated,
                       int sinkFailureCount) {
            this(diffResult, writtenRecordCount, truncated, sinkFailureCount, null);
        }

        CompareOutcome(DiffResult diffResult, long writtenRecordCount, boolean truncated,
                       int sinkFailureCount, Map<String, Object> statistics) {
            this.diffResult = diffResult;
            this.writtenRecordCount = writtenRecordCount;
            this.truncated = truncated;
            this.sinkFailureCount = sinkFailureCount;
            this.statistics = statistics;
        }
    }

    protected CompareOutcome executeComparison(TaskExecutionContext taskContext,
                                               ServerCompareConfig config,
                                               RunRequest.Options options) throws Exception {
        CompareRequest compareRequest = configService.toCompareRequest(config, options);
        DiffLifecycle lifecycle = buildLifecycle(config, attemptScopedId(taskContext));
        DiffContext diffContext = buildDiffContext(taskContext, config);
        Throwable failure = null;
        CompareOutcome outcome = null;
        try {
            assertNotCancellationRequested(taskContext);
            lifecycle.onDiffStart(diffContext);
            DiffResult diffResult = enrichResult(
                    createCompareRuntime().execute(compareRequest), compareRequest);
            assertNotCancellationRequested(taskContext);
            publishDifferences(diffResult, lifecycle, diffContext);
            assertNotCancellationRequested(taskContext);
            lifecycle.onDiffComplete(diffResult, diffContext);
            outcome = new CompareOutcome(diffResult,
                    lifecycle instanceof DefaultDiffLifecycle
                            ? ((DefaultDiffLifecycle) lifecycle).writtenRecordCount()
                            : 0L,
                    lifecycle instanceof DefaultDiffLifecycle
                            && ((DefaultDiffLifecycle) lifecycle).isTruncated(),
                    lifecycle instanceof DefaultDiffLifecycle
                            ? ((DefaultDiffLifecycle) lifecycle).sinkFailureCount()
                            : 0);
        } catch (Exception exception) {
            failure = exception;
            try {
                lifecycle.onDiffError(diffContext, exception);
            } catch (Exception lifecycleException) {
                exception.addSuppressed(lifecycleException);
            }
            throw exception;
        } finally {
            try {
                lifecycle.close();
            } catch (Exception lifecycleException) {
                if (failure != null) {
                    failure.addSuppressed(lifecycleException);
                } else {
                    throw lifecycleException;
                }
            }
        }
        // close 阶段也可能写失败（例如文件 flush/句柄关闭）；failOnSinkError=false 时
        // close 不抛异常，但最终失败数必须带回结果元数据。
        if (outcome != null && lifecycle instanceof DefaultDiffLifecycle) {
            int sinkFailures = ((DefaultDiffLifecycle) lifecycle).sinkFailureCount();
            if (sinkFailures != outcome.sinkFailureCount) {
                outcome = new CompareOutcome(outcome.diffResult, outcome.writtenRecordCount,
                        outcome.truncated, sinkFailures, outcome.statistics);
            }
        }
        return outcome;
    }

    protected CompareRuntime createCompareRuntime() {
        return new DefaultCompareRuntime();
    }

    /**
     * Attaches exact per-column statistics, the run fingerprint and execution
     * evidence before the result is written to the artifact. All three are
     * post-processors over the completed result; none changes the comparison
     * algorithm.
     */
    DiffResult enrichResult(DiffResult result, CompareRequest request) {
        if (result == null) {
            return null;
        }
        long maxDifferences = request.getExecutionOptions() != null
                && request.getExecutionOptions().getMaxDifferences() != null
                ? Math.max(1L, request.getExecutionOptions().getMaxDifferences())
                : 1_000_000L;
        return result.toBuilder()
                .columnStats(ColumnStatsCalculator.calculate(result, request, maxDifferences))
                .fingerprint(RunFingerprintFactory.create(request, EngineInfo.current().getVersion()))
                .evidence(DiffEvidenceFactory.from(result))
                .build();
    }

    protected DiffLifecycle buildLifecycle(ServerCompareConfig config, String taskId) {
        ResultConfig resultConfig = ResultConfig.builder()
                // 沿用用户的 failOnSinkError 配置；用户未配置时保持既有默认（true）
                .failOnSinkError(config.getResult() == null || config.getResult().isFailOnSinkError())
                .sinks(composeSinks(config, taskId))
                .build();
        return new DefaultDiffLifecycle(resultConfig);
    }

    /**
     * 用户配置的 sink + 必然存在的差异明细落盘 sink。实例详情的差异明细分页读取的是
     * 默认路径的那个 jsonl 文件（不截断），因此无论用户配了什么展示型 sink，
     * 都必须保证有一个指向默认路径的 diff-record sink。
     */
    List<SinkConfig> composeSinks(ServerCompareConfig config) {
        return composeSinks(config, null);
    }

    List<SinkConfig> composeSinks(ServerCompareConfig config, String taskId) {
        List<SinkConfig> sinks = new ArrayList<>();
        if (config.getResult() != null && config.getResult().getSinks() != null) {
            sinks.addAll(config.getResult().getSinks());
        }
        if (sinks.isEmpty()) {
            // 用户未配置 sink：保留 console 结果输出
            sinks.add(SinkConfig.builder()
                    .format("console")
                    .type("result")
                    .enabled(true)
                    .build());
        }
        // 用户自配的 console 等展示 sink 不影响差异明细落盘；用户自配的 jsonl sink
        // 只有指向同一默认路径才算替代——否则报告会退化成 1000 行内联样本。
        boolean hasDefaultDiffRecord = sinks.stream()
                .anyMatch(sink -> "jsonl".equals(sink.getFormat()) && sink.isEnabled()
                        && normalizedPath(renderTaskId(sinkPath(sink), taskId))
                        .equals(normalizedPath(defaultDiffPath(taskId))));
        if (!hasDefaultDiffRecord) {
            sinks.add(SinkConfig.builder()
                    .format("jsonl")
                    .type("diff-record")
                    .enabled(true)
                    .properties(toJson(Map.of(
                            "path", defaultDiffPath(taskId))))
                    .build());
        }
        return sinks;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to serialize sink properties", exception);
        }
    }

    /**
     * 默认明细路径。运行时用真实 taskId 渲染；没有 taskId（单测直接调用）时保留
     * {@code ${taskId}} 占位符，由 sink 在写入时插值。
     */
    private String defaultDiffPath(String taskId) {
        String id = taskId == null || taskId.isBlank() ? "${taskId}" : taskId;
        // 必须与 resolvedDiffUri 一样输出绝对路径：base-dir 默认是相对路径，
        // 否则用户配置同一文件的绝对 jsonl sink 时会被判为“不同路径”，
        // 两个 sink 并发写同一文件导致明细重复/交错。
        return Paths.get(properties.getArtifact().getLocalBaseDir(), "run-result-" + id + ".jsonl")
                .toAbsolutePath().toString();
    }

    /** 仅用于比较：把用户 sink 路径中的 {@code ${taskId}} 渲染成同一个真实 id。 */
    private static String renderTaskId(String path, String taskId) {
        if (path == null || taskId == null || taskId.isBlank()) {
            return path;
        }
        return path.replace("${taskId}", taskId);
    }

    /** 相对/绝对、`.`/`..` 等写法不同但指向同一文件的路径要能判等。 */
    private static String normalizedPath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        return Paths.get(path).toAbsolutePath().normalize().toString();
    }

    /**
     * 读 sink 配置里的 {@code path}（properties 是 JSON 字符串）；解析失败或未配置时返回 null，
     * 表示"不指向默认明细文件"。
     */
    private String sinkPath(SinkConfig sink) {
        if (sink == null || sink.getProperties() == null || sink.getProperties().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sink.getProperties());
            JsonNode path = node.get("path");
            return path != null && path.isTextual() ? path.textValue() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 差异明细文件名里的运行标识。重试复用同一 instanceKey（retry 只换 traceId），
     * 只用 instanceKey 会让重试覆盖上一次 attempt 的明细文件；把每次 attempt 的 traceId
     * 拼进去，重试各自落盘、报告 reader 仍从本次 artifact 里的 URI 读取。
     */
    static String attemptScopedId(TaskExecutionContext context) {
        String instanceKey = context.getInstanceKey();
        String traceId = context.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            return instanceKey;
        }
        String suffix = traceId.length() > 12 ? traceId.substring(0, 12) : traceId;
        return instanceKey + "-" + suffix;
    }

    private String resolvedDiffUri(String runId) {
        // 绝对路径 + 每次运行唯一 id，避免重试覆盖与 cwd 变化导致 URI 失效
        return Paths.get(properties.getArtifact().getLocalBaseDir(),
                "run-result-" + runId + ".jsonl").toAbsolutePath().toString();
    }

    private DiffContext buildDiffContext(TaskExecutionContext taskContext, ServerCompareConfig config) {
        return DiffContext.builder()
                .taskId(taskContext.getInstanceKey())
                .startTime(taskContext.getStartTime())
                .sourceTablePath(tablePath(config.getSource().getTable()))
                .targetTablePath(tablePath(config.getTarget().getTable()))
                .strategy(stringValue(config.getHints().get("strategy")))
                .algorithm(stringValue(config.getExecutionOptions().get("checksumAlgorithm")))
                .sourceColumnNames(columns(config))
                .targetColumnNames(columns(config))
                .build();
    }

    private TablePath tablePath(String table) {
        return table == null || table.isBlank() ? null : TablePath.fromString(table);
    }

    private List<String> columns(ServerCompareConfig config) {
        List<String> columns = new ArrayList<>(config.getKeys());
        if (config.getComparison() != null && config.getComparison().getFields() != null) {
            for (String field : config.getComparison().getFields()) {
                if (!columns.contains(field)) {
                    columns.add(field);
                }
            }
        }
        return columns;
    }

    private void publishDifferences(DiffResult result,
                                    DiffLifecycle lifecycle,
                                    DiffContext context) throws Exception {
        if (result != null && result.getDifferences() != null && !result.getDifferences().isEmpty()) {
            lifecycle.onDifferencesFound(result.getDifferences(), context);
        }
    }

    private void assertNotCancellationRequested(TaskExecutionContext context) {
        if (context == null || context.getTaskId() == null) {
            return;
        }
        boolean cancellationRequested = taskRepository.findById(context.getTaskId())
                .map(task -> task.getStatus() == TaskStatus.CANCEL_REQUESTED)
                .orElse(false);
        if (cancellationRequested) {
            throw new TaskCancellationException(context.getInstanceKey());
        }
    }

    private TaskExecutionResult writeSuccess(TaskExecutionContext context,
                                             RunRequest request,
                                             Instant startedAt,
                                             boolean dryRun,
                                             Map<String, Object> details,
                                             CompareOutcome outcome) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", true);
        content.put("dryRun", dryRun);
        if (outcome != null && outcome.sinkFailureCount > 0) {
            // failOnSinkError=false 时 sink 失败不会抛，但明细可能不完整，必须让调用方看见。
            content.put("sinkFailed", true);
            content.put("sinkFailureCount", outcome.sinkFailureCount);
        }
        content.putAll(details);
        // 差异明细不再内联进 artifact：仅当默认 jsonl 文件确实落盘时才外置
        String diffFilePath = resolvedDiffUri(attemptScopedId(context));
        boolean diffFileWritten = outcome != null && outcome.writtenRecordCount > 0
                && Files.exists(Paths.get(diffFilePath));
        if (diffFileWritten) {
            content.remove("differences");
        }
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run",
                        "resultSchemaVersion", DiffResult.RESULT_SCHEMA_VERSION));
        // 无 outcome（dryRun、集群未回传统计）时 statistics 必须保持 null：
        // 空 map 会被 artifact 元数据当成"0 差异"，报告页将显示 100% 匹配。
        Map<String, Object> statistics = null;
        if (outcome != null && outcome.diffResult != null) {
            statistics = outcome.diffResult.getStatisticsMap();
        } else if (outcome != null) {
            statistics = outcome.statistics;
        }
        String differencesUri = diffFileWritten ? diffFilePath : null;
        artifactService.updateRunResultMeta(artifact.getId(),
                statistics,
                outcome != null && outcome.truncated,
                outcome != null ? outcome.writtenRecordCount : 0L,
                differencesUri);
        return CapabilityResultSupport.result(artifact);
    }

    /**
     * 从 RM diagnostics 里解析 AM 写入的 {"submissionId":...,"statistics":{...}}。
     * 解析失败返回空 map，由调用方走"统计不可用"分支。
     */
    Map<String, Object> parseClusterStatistics(String diagnostics) {
        if (diagnostics == null || diagnostics.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(diagnostics);
            com.fasterxml.jackson.databind.JsonNode statistics = root.path("statistics");
            if (!statistics.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(statistics, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("failed to parse cluster diagnostics summary: {}", e.getMessage());
            return Map.of();
        }
    }

    private ArtifactRefDto writeFailure(TaskExecutionContext context,
                                        RunRequest request,
                                        Instant startedAt,
                                        String errorCode,
                                        String errorMessage) {
        return writeFailure(context, request, startedAt, errorCode, errorMessage, Map.of());
    }

    /** 失败结果也可附带执行详情（如集群应用 ID），便于详情页定位失败的应用。 */
    private ArtifactRefDto writeFailure(TaskExecutionContext context,
                                        RunRequest request,
                                        Instant startedAt,
                                        String errorCode,
                                        String errorMessage,
                                        Map<String, Object> details) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", false);
        content.put("dryRun", Boolean.TRUE.equals(options(request).getDryRun()));
        content.put("errorCode", errorCode);
        // 失败结果经 API 可见：驱动异常可能带 JDBC URL/查询参数凭据，统一脱敏
        content.put("errorMessage", JdbcConnectionSupport.maskMessage(errorMessage));
        if (details != null && !details.isEmpty()) {
            content.putAll(details);
        }
        return artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run", "errorCode", errorCode));
    }

    private Map<String, Object> baseContent(RunRequest request, Instant startedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("serialNo", request.getSerialNo());
        content.put("configArtifactId", request.getConfigArtifactId());
        content.put("startedAt", startedAt.toString());
        content.put("completedAt", Instant.now().toString());
        return content;
    }

    Map<String, Object> runResult(DiffResult diffResult) {
        Map<String, Object> content = new LinkedHashMap<>();
        boolean hasDifferences = diffResult != null && diffResult.hasDifferences();
        long differenceCount = diffResult != null ? diffResult.getDifferenceCount() : 0L;
        content.put("hasDifferences", hasDifferences);
        content.put("differenceCount", differenceCount);
        content.put("resultSchemaVersion", DiffResult.RESULT_SCHEMA_VERSION);
        content.put("statistics", diffResult != null ? diffResult.getStatisticsMap() : Map.of());
        content.put("columnStats", diffResult != null ? diffResult.getColumnStats() : List.of());
        content.put("schemaCompatibility",
                diffResult != null ? diffResult.getSchemaCompatibility() : null);
        content.put("fingerprint", diffResult != null ? diffResult.getFingerprint() : null);
        content.put("evidence", diffResult != null ? diffResult.getEvidence() : null);
        content.put("metadata", diffResult != null ? diffResult.getMetadata() : Map.of());
        List<DiffRow> allDifferences = diffResult != null && diffResult.getDifferences() != null
                ? diffResult.getDifferences()
                : List.of();
        // Keep the artifact bounded: persist the full statistics plus a sampled
        // detail list instead of serializing millions of diff rows into a 50MB cap.
        content.put("differenceSampleSize", (int) Math.min(differenceCount, diffSampleRows));
        content.put("differenceSampleTruncated", allDifferences.size() > diffSampleRows);
        content.put("differences", allDifferences.stream()
                .limit(diffSampleRows)
                .map(this::diffRow)
                .collect(Collectors.toList()));
        return content;
    }

    Map<String, Object> diffRow(DiffRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", row.getOperation().name());
        result.put("primaryKey", row.getPrimaryKey());
        // Persist source/target values and column names so the diff-report
        // samples can render 旧值/新值 instead of only the primary key.
        result.put("sourceValues", row.getSourceValues().orElse(null));
        result.put("targetValues", row.getTargetValues().orElse(null));
        // 单一 columnNames 键：值数组两侧同序（键列 + 比对列），UI 按下标标注字段名
        result.put("columnNames", row.resolvedColumnNames());
        result.put("metadata", row.getMetadata());
        return result;
    }

    private RunRequest.Options options(RunRequest request) {
        return request.getOptions() != null ? request.getOptions() : new RunRequest.Options();
    }
}
