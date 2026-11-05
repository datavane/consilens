package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.capability.config.SinkSecretSupport;
import com.consilens.server.application.connection.DatasourceConnectionPolicy;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.consilens.server.support.crypto.SecretProtector;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PlanTaskHandler implements CapabilityHandler<PlanRequest> {

    private static final String ENCRYPTED_PREFIX = "v2:";

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;
    private final SecretProtector secretProtector;

    public PlanTaskHandler(ArtifactService artifactService,
                           ServerCompareConfigService configService,
                           SecretProtector secretProtector) {
        this.artifactService = artifactService;
        this.configService = configService;
        this.secretProtector = secretProtector;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.PLAN;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, PlanRequest request) {
        ServerCompareConfig config = configService.fromPlanRequest(request);
        protectSecrets(config);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.CONFIG,
                "json",
                config,
                Map.of("source", "plan"));
        return CapabilityResultSupport.result(artifact);
    }

    /**
     * CONFIG artifact 会持久化并可经 API 读取，密码不能明文落盘：统一转成 v2: 密文，
     * 运行期由 RunTaskHandler.injectEndpoint 解密（与外部直传的语义一致）。
     */
    private void protectSecrets(ServerCompareConfig config) {
        protectConnection(config.getSource());
        protectConnection(config.getTarget());
        if (config.getResult() != null) {
            SinkSecretSupport.transformSinkConfigs(config.getResult().getSinks(),
                    this::protectSinkPassword);
        }
        Map<String, Object> hints = config.getHints();
        if (hints == null || hints.isEmpty()) {
            return;
        }
        protectHintPassword(hints, "sourcePassword");
        protectHintPassword(hints, "targetPassword");
        protectHintConnection(hints, "sourceConnection");
        protectHintConnection(hints, "targetConnection");
    }

    private void protectConnection(EndpointConfig endpoint) {
        if (endpoint == null || endpoint.getConnection() == null) {
            return;
        }
        validateConnectionProperties(endpoint.getConnection());
        protectPassword(endpoint.getConnection());
    }

    @SuppressWarnings("unchecked")
    private void protectHintConnection(Map<String, Object> hints, String key) {
        Object raw = hints.get(key);
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> connection = new LinkedHashMap<>((Map<String, Object>) raw);
        validateConnectionProperties(connection);
        if (protectPassword(connection)) {
            hints.put(key, connection);
        }
    }

    private void protectHintPassword(Map<String, Object> hints, String key) {
        Object value = hints.get(key);
        if (value instanceof String && isCredentialKey(key)) {
            String password = (String) value;
            if (protectable(password)) {
                hints.put(key, secretProtector.protect(password));
            }
        }
    }

    /**
     * properties 字符串会原样进入 CONFIG artifact；敏感键即使不叫 password
     * （sslpassword/token 等）也不能明文落盘。
     */
    @SuppressWarnings("unchecked")
    private void validateConnectionProperties(Map<String, Object> connection) {
        Object raw = connection.get("properties");
        if (raw == null) {
            return;
        }
        if (raw instanceof String) {
            String text = (String) raw;
            if (!text.isBlank()) {
                try {
                    DatasourceConnectionPolicy.validateProperties(text);
                } catch (IllegalArgumentException exception) {
                    throw new InvalidInputException(exception.getMessage());
                }
            }
            return;
        }
        if (raw instanceof Map) {
            for (Object key : ((Map<?, ?>) raw).keySet()) {
                if (DatasourceConnectionPolicy.isSensitivePropertyKey(String.valueOf(key))) {
                    throw new InvalidInputException(
                            "connection.properties contains sensitive key: " + key);
                }
            }
            return;
        }
        throw new InvalidInputException("connection.properties must be a string or object");
    }

    /**
     * 加密连接里所有"看起来是凭据"的字符串值。
     *
     * <p>只处理 {@code password} 会漏掉 {@code sslPassword} / {@code trustStorePassword} /
     * {@code accessToken} / {@code clientSecret} 这类同样出现在 JDBC 连接参数里的键——它们会以明文
     * 进入 CONFIG artifact，而 artifact 是可读的文件。
     */
    private boolean protectPassword(Map<String, Object> connection) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : connection.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof String) || !isCredentialKey(key)) {
                continue;
            }
            String text = (String) value;
            if (protectable(text)) {
                entry.setValue(secretProtector.protect(text));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isCredentialKey(String key) {
        if (key == null) {
            return false;
        }
        return "password".equalsIgnoreCase(key)
                || DatasourceConnectionPolicy.isSensitivePropertyKey(key);
    }

    /** 掩码占位/空值直接移除；已加密的保持，避免二次加密。 */
    private String protectSinkPassword(String password) {
        if (password == null || password.isBlank() || password.matches("\\*+")) {
            return null;
        }
        return password.startsWith(ENCRYPTED_PREFIX)
                ? password : secretProtector.protect(password);
    }

    private boolean protectable(String password) {
        return !password.isBlank() && !password.startsWith(ENCRYPTED_PREFIX);
    }
}
