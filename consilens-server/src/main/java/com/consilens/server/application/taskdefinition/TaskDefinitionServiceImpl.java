package com.consilens.server.application.taskdefinition;

import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.capability.config.SinkSecretSupport;
import com.consilens.server.application.capability.config.TaskDefinitionConfigValidator;
import com.consilens.server.application.connection.DatasourceConnectionPolicy;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskDefinitionPage;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.crypto.SecretProtector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskDefinitionServiceImpl implements TaskDefinitionService {

    /** 任务定义详情页展示的最近执行实例条数。 */
    private static final int RECENT_INSTANCE_LIMIT = 5;

    /** 自动生成 serialNo 时任务名前缀的最大长度。 */
    private static final int SERIAL_NAME_MAX_LENGTH = 40;

    private final TaskDefinitionRepository definitionRepository;
    private final TaskRepository taskRepository;
    private final RunTaskSubmissionService submissionService;
    private final ServerCompareConfigService configService;
    private final TaskDefinitionConfigValidator configValidator;
    private final ObjectMapper objectMapper;
    private final SecretProtector secretProtector;

    @Autowired
    public TaskDefinitionServiceImpl(TaskDefinitionRepository definitionRepository,
                                        TaskRepository taskRepository,
                                        RunTaskSubmissionService submissionService,
                                        ServerCompareConfigService configService,
                                        TaskDefinitionConfigValidator configValidator,
                                        ObjectMapper objectMapper,
                                        SecretProtector secretProtector) {
        this.definitionRepository = definitionRepository;
        this.taskRepository = taskRepository;
        this.submissionService = submissionService;
        this.configService = configService;
        this.configValidator = configValidator;
        this.objectMapper = objectMapper;
        this.secretProtector = secretProtector;
    }

    @Override
    public TaskDefinitionDto create(TaskDefinitionCreateRequest request) {
        definitionRepository.findByName(request.getName()).ifPresent(record -> {
            throw new IllegalArgumentException("task definition name already exists: " + request.getName());
        });
        request.setConfig(new java.util.LinkedHashMap<>(request.getConfig()));
        validateConfig(request.getConfig());
        TaskDefinitionRecord saved = saveDefinition(TaskDefinitionRecord.builder()
                .definitionKey("taskdef_" + UUID.randomUUID())
                .name(request.getName())
                .description(request.getDescription())
                .taskType("RUN")
                .config(toConfigJson(request.getConfig()))
                .enabled(true)
                .build());
        return toDto(saved);
    }

    @Override
    public PageResponse<TaskDefinitionDto> list(int page, int pageSize, String keyword, Boolean enabled) {
        TaskDefinitionPage result = definitionRepository.listPage(page, pageSize, keyword, enabled);
        return PageResponse.<TaskDefinitionDto>builder()
                .total(result.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(result.getItems().stream().map(this::toDto).collect(Collectors.toList()))
                .build();
    }

    @Override
    public TaskDefinitionDetailDto get(Long id) {
        TaskDefinitionRecord record = require(id);
        List<TaskDefinitionDetailDto.RecentInstanceDto> recent = taskRepository
                .listByDefinitionId(id, RECENT_INSTANCE_LIMIT)
                .stream()
                .map(this::toRecentInstance)
                .collect(Collectors.toList());
        Map<String, Object> config = fromConfigJson(record.getConfig());
        maskInlinePasswords(config);
        maskHintPasswords(config);
        maskSinkPasswords(config);
        return TaskDefinitionDetailDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .description(record.getDescription())
                .taskType(record.getTaskType())
                .enabled(record.getEnabled())
                .lastRunAt(record.getLastRunAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .config(config)
                .recentInstances(recent)
                .build();
    }

    @Override
    public TaskDefinitionDto update(Long id, TaskDefinitionCreateRequest request) {
        TaskDefinitionRecord record = require(id);
        definitionRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("task definition name already exists: " + request.getName());
                });
        // GET 回显的 mask 原样 PUT：用旧配置的密文回填，避免编辑时清空 sink 密码。
        request.setConfig(new java.util.LinkedHashMap<>(request.getConfig()));
        SinkSecretSupport.restoreMaskedPasswords(request.getConfig(), fromConfigJson(record.getConfig()));
        validateConfig(request.getConfig());
        record.setName(request.getName());
        record.setDescription(request.getDescription());
        record.setConfig(toConfigJson(request.getConfig()));
        return toDto(saveDefinition(record));
    }

    /** 预检查与写入之间仍可能被并发请求抢占：唯一键冲突要映射成 400，而不是 500。 */
    private TaskDefinitionRecord saveDefinition(TaskDefinitionRecord record) {
        try {
            return definitionRepository.save(record);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "task definition name already exists: " + record.getName(), exception);
        }
    }

    /** 定义配置中的执行平台参数 → RunRequest.Options（platform/properties 保存于定义 config）。 */
    @SuppressWarnings("unchecked")
    private void applyPlatformOptions(RunRequest runRequest, TaskDefinitionRecord record) {
        if (runRequest.getOptions() == null) {
            runRequest.setOptions(new RunRequest.Options());
        }
        Map<String, Object> config = fromConfigJson(record.getConfig());
        // 列表页“运行”不传 options；定义的超时配置必须在这里回填，否则集群提交拿到 null
        if (runRequest.getOptions().getTimeoutMs() == null
                && config.get("executionOptions") instanceof Map) {
            Object timeout = ((Map<String, Object>) config.get("executionOptions")).get("timeoutMs");
            Integer parsed = parseTimeoutMs(timeout);
            if (parsed != null) {
                runRequest.getOptions().setTimeoutMs(parsed);
            }
        }
        Object platform = config.get("platform");
        if (platform == null || "local".equals(String.valueOf(platform))) {
            return;
        }
        runRequest.getOptions().setPlatform(String.valueOf(platform));
        if (runRequest.getOptions().getProperties() == null && config.get("properties") instanceof Map) {
            runRequest.getOptions().setProperties((Map<String, Object>) config.get("properties"));
        }
    }

    @Override
    public void delete(Long id) {
        definitionRepository.deleteById(id);
    }

    @Override
    public TaskAcceptedResponse run(Long id, TaskDefinitionRunRequest request, String traceId) {
        TaskDefinitionRecord record = require(id);
        if (!Boolean.TRUE.equals(record.getEnabled())) {
            throw new IllegalArgumentException("task definition is disabled: " + record.getName());
        }
        RunRequest runRequest = new RunRequest();
        runRequest.setSerialNo(request != null && request.getSerialNo() != null && !request.getSerialNo().isBlank()
                ? request.getSerialNo()
                : "def-" + record.getName()
                        .substring(0, Math.min(record.getName().length(), SERIAL_NAME_MAX_LENGTH))
                        + "-" + UUID.randomUUID().toString().substring(0, 8));
        runRequest.setConfigContent(fromConfigJson(record.getConfig()));
        runRequest.setDefinitionId(record.getId());
        if (request != null) {
            runRequest.setOptions(request.getOptions());
        }
        applyPlatformOptions(runRequest, record);
        TaskAcceptedResponse accepted = submissionService.submit(runRequest, traceId);
        definitionRepository.updateLastRunAt(id, java.time.Instant.now());
        return accepted;
    }

    @Override
    public TaskDefinitionDto toggle(Long id, boolean enabled) {
        TaskDefinitionRecord record = require(id);
        record.setEnabled(enabled);
        return toDto(definitionRepository.save(record));
    }

    private void validateConfig(Map<String, Object> config) {
        sanitizeHintCredentials(config);
        sanitizeInlinePasswords(config, "source");
        sanitizeInlinePasswords(config, "target");
        protectSinkPasswords(config);
        validateExecutionTimeout(config);
        validatePlatform(config);
        configValidator.validateDatasourceRefs(config);
        validateConfigSchema(config);
    }

    @SuppressWarnings("unchecked")
    private void validateExecutionTimeout(Map<String, Object> config) {
        Object executionOptions = config.get("executionOptions");
        if (executionOptions instanceof Map) {
            parseTimeoutMs(((Map<String, Object>) executionOptions).get("timeoutMs"));
        }
    }

    private void validatePlatform(Map<String, Object> config) {
        Object raw = config.get("platform");
        String platform = raw == null ? null : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (platform == null || platform.isBlank()) {
            config.remove("platform");
        } else {
            if (!java.util.Set.of("local", "yarn", "kubernetes").contains(platform)) {
                throw new IllegalArgumentException("platform must be local, yarn or kubernetes");
            }
            config.put("platform", platform);
        }
        Object properties = config.get("properties");
        if (properties != null && !(properties instanceof Map)) {
            throw new IllegalArgumentException("properties must be an object");
        }
    }

    /** timeoutMs 必须为正整数；数字字符串也接受，避免旧 YAML 配置被静默忽略。 */
    private static Integer parseTimeoutMs(Object value) {
        if (value == null) {
            return null;
        }
        long parsed;
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            // 1.5ms 这类小数不能 longValue() 截成 1ms，否则运行会几乎立即超时。
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
                throw new IllegalArgumentException("executionOptions.timeoutMs must be a positive integer");
            }
            parsed = (long) numeric;
        } else if (value instanceof String && !((String) value).isBlank()) {
            try {
                parsed = Long.parseLong(((String) value).trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("executionOptions.timeoutMs must be a positive integer");
            }
        } else {
            throw new IllegalArgumentException("executionOptions.timeoutMs must be a positive integer");
        }
        if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("executionOptions.timeoutMs must be a positive integer");
        }
        return (int) parsed;
    }

    /**
     * hints 里的连接密码同样不能明文落库：真实值直接拒绝；回显的 ****** 占位则移除
     * （否则会被当成真实密码保存/执行）。与 PlanTaskHandler 的加密写入语义保持一致。
     */
    @SuppressWarnings("unchecked")
    private void sanitizeHintCredentials(Map<String, Object> config) {
        Object raw = config.get("hints");
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> hints = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        for (String key : List.of("sourcePassword", "targetPassword")) {
            if (!hints.containsKey(key)) {
                continue;
            }
            Object value = hints.get(key);
            if (value == null || (value instanceof String && ((String) value).matches("\\*+"))) {
                hints.remove(key);
            } else {
                throw inlineHintPasswordNotAllowed();
            }
        }
        for (String key : List.of("sourceConnection", "targetConnection")) {
            Object connection = hints.get(key);
            if (!(connection instanceof Map)) {
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) connection);
            validateConnectionProperties(copy);
            Object password = copy.get("password");
            if (password == null || (password instanceof String && ((String) password).matches("\\*+"))) {
                copy.remove("password");
                hints.put(key, copy);
            } else {
                throw inlineHintPasswordNotAllowed();
            }
        }
        config.put("hints", hints);
    }

    /** properties 中的敏感键会随定义明文落库；字符串形式也复用连接参数白名单。 */
    @SuppressWarnings("unchecked")
    private void validateConnectionProperties(Map<String, Object> connection) {
        Object raw = connection.get("properties");
        if (raw == null) {
            return;
        }
        if (raw instanceof String) {
            String stripped = stripMaskedSensitiveProperties((String) raw);
            if (stripped.isBlank()) {
                connection.remove("properties");
                return;
            }
            try {
                DatasourceConnectionPolicy.validateProperties(stripped);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(exception.getMessage());
            }
            connection.put("properties", stripped);
            return;
        }
        if (raw instanceof Map) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
            copy.entrySet().removeIf(entry ->
                    DatasourceConnectionPolicy.isSensitivePropertyKey(String.valueOf(entry.getKey()))
                            && entry.getValue() instanceof String
                            && ((String) entry.getValue()).matches("\\*+"));
            for (Object key : copy.keySet()) {
                if (DatasourceConnectionPolicy.isSensitivePropertyKey(String.valueOf(key))) {
                    throw new IllegalArgumentException(
                            "connection.properties contains sensitive key: " + key);
                }
            }
            if (copy.isEmpty()) {
                connection.remove("properties");
            } else {
                connection.put("properties", copy);
            }
            return;
        }
        throw new IllegalArgumentException("connection.properties must be a string or object");
    }

    /** 移除历史回显的敏感键掩码占位（key=******），真实值仍交给白名单校验拒绝。 */
    private static String stripMaskedSensitiveProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            return "";
        }
        List<String> kept = new java.util.ArrayList<>();
        for (String pair : properties.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
            String value = separator > 0 ? pair.substring(separator + 1).trim() : "";
            if (DatasourceConnectionPolicy.isSensitivePropertyKey(key) && value.matches("\\*+")) {
                continue;
            }
            kept.add(pair);
        }
        return String.join("&", kept);
    }

    private InvalidInputException inlineHintPasswordNotAllowed() {
        return new InvalidInputException(
                "task definition config must reference data sources via datasourceId; "
                        + "inline hint passwords are not allowed");
    }

    /** 历史数据回显：hints 中的密码只返回掩码，不返回明文。 */
    @SuppressWarnings("unchecked")
    private void maskHintPasswords(Map<String, Object> config) {
        Object raw = config.get("hints");
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> hints = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        for (String key : List.of("sourcePassword", "targetPassword")) {
            if (hints.get(key) != null) {
                hints.put(key, "******");
            }
        }
        for (String key : List.of("sourceConnection", "targetConnection")) {
            Object connection = hints.get(key);
            if (!(connection instanceof Map)) {
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) connection);
            boolean changed = false;
            if (copy.containsKey("password")) {
                copy.put("password", "******");
                changed = true;
            }
            changed |= maskConnectionProperties(copy);
            if (changed) {
                hints.put(key, copy);
            }
        }
        config.put("hints", hints);
    }

    /**
     * 历史定义可能内联了连接密码：真实值拒绝写入；回显的 ****** 占位则移除，
     * 使旧定义可以正常编辑保存。
     */
    @SuppressWarnings("unchecked")
    private void sanitizeInlinePasswords(Map<String, Object> config, String side) {
        Object raw = config.get(side);
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> endpoint = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        Object connection = endpoint.get("connection");
        if (!(connection instanceof Map)) {
            return;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) connection);
        validateConnectionProperties(copy);
        Object password = copy.get("password");
        if (password == null) {
            // validateConnectionProperties 可能移除了 properties 里的掩码敏感键
            endpoint.put("connection", copy);
            config.put(side, endpoint);
            return;
        }
        if (password instanceof String && ((String) password).matches("\\*+")) {
            copy.remove("password");
            endpoint.put("connection", copy);
            config.put(side, endpoint);
            return;
        }
        throw new IllegalArgumentException(
                "task definition config must reference data sources via datasourceId; "
                        + "inline connection passwords are not allowed");
    }

    /** 历史定义回显：内联连接密码只返回掩码。 */
    @SuppressWarnings("unchecked")
    private void maskInlinePasswords(Map<String, Object> config) {
        for (String side : List.of("source", "target")) {
            Object raw = config.get(side);
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> endpoint = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
            Object connection = endpoint.get("connection");
            if (!(connection instanceof Map)) {
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) connection);
            boolean changed = false;
            if (copy.containsKey("password")) {
                copy.put("password", "******");
                changed = true;
            }
            changed |= maskConnectionProperties(copy);
            if (changed) {
                endpoint.put("connection", copy);
                config.put(side, endpoint);
            }
        }
    }

    /** 历史定义回显：properties 中的敏感键只返回掩码。 */
    @SuppressWarnings("unchecked")
    private boolean maskConnectionProperties(Map<String, Object> connection) {
        Object raw = connection.get("properties");
        if (raw instanceof String) {
            String text = (String) raw;
            if (text.isBlank()) {
                return false;
            }
            List<String> masked = new java.util.ArrayList<>();
            boolean changed = false;
            for (String pair : text.split("&")) {
                if (pair == null || pair.isBlank()) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
                if (DatasourceConnectionPolicy.isSensitivePropertyKey(key)) {
                    masked.add(key + "=******");
                    changed = true;
                } else {
                    masked.add(pair);
                }
            }
            if (changed) {
                connection.put("properties", String.join("&", masked));
            }
            return changed;
        }
        if (raw instanceof Map) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
            boolean changed = false;
            for (Map.Entry<String, Object> entry : copy.entrySet()) {
                if (DatasourceConnectionPolicy.isSensitivePropertyKey(entry.getKey())) {
                    entry.setValue("******");
                    changed = true;
                }
            }
            if (changed) {
                connection.put("properties", copy);
            }
            return changed;
        }
        return false;
    }

    /**
     * 用**执行期同一套解析**校验定义，而不是再走一次 objectMapper 直转。
     *
     * <p>此前这里固定 {@code convertValue(config, ServerCompareConfig.class)}，只认老的服务端形状；
     * 与 CLI 同构的配置（{@code source/target} + {@code resource}、{@code comparison.keys/fields} 成对）
     * 会在写入阶段直接反序列化失败，逼得调用方维护两套 schema。
     */
    private void validateConfigSchema(Map<String, Object> config) {
        try {
            ServerCompareConfig parsed = configService.fromContent(config);
            configService.validate(parsed);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid task definition config: " + exception.getMessage());
        }
    }

    /** 表 sink 的 JDBC 密码落库前加密；回显掩码/空值直接移除。 */
    private void protectSinkPasswords(Map<String, Object> config) {
        SinkSecretSupport.transformConfigSinkPasswords(config, password -> {
            if (password == null || password.isBlank() || password.matches("\\*+")) {
                return null;
            }
            return password.startsWith("v2:") ? password : secretProtector.protect(password);
        });
    }

    /** 定义详情回显时表 sink 密码只返回掩码。 */
    private void maskSinkPasswords(Map<String, Object> config) {
        SinkSecretSupport.transformConfigSinkPasswords(config,
                password -> password == null ? null : "******");
    }

    private TaskDefinitionRecord require(Long id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("task definition not found: " + id));
    }

    private TaskDefinitionDto toDto(TaskDefinitionRecord record) {
        return TaskDefinitionDto.builder()
                .id(String.valueOf(record.getId()))
                .name(record.getName())
                .description(record.getDescription())
                .taskType(record.getTaskType())
                .enabled(record.getEnabled())
                .lastRunAt(record.getLastRunAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private TaskDefinitionDetailDto.RecentInstanceDto toRecentInstance(TaskInstanceRecord record) {
        return TaskDefinitionDetailDto.RecentInstanceDto.builder()
                .instanceId(record.getInstanceKey())
                .serialNo(record.getSerialNo())
                .status(record.getStatus() != null ? record.getStatus().name() : null)
                .submitTime(record.getSubmitTime())
                .endTime(record.getEndTime())
                .build();
    }

    private String toConfigJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid task definition config");
        }
    }

    private Map<String, Object> fromConfigJson(String config) {
        try {
            return objectMapper.readValue(config, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new IllegalStateException("task definition config is corrupted");
        }
    }
}
