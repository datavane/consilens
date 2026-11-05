package com.consilens.server.application.config;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ConfigResponse;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.connection.DatasourceConnectionPolicy;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ConfigArtifactServiceImpl implements ConfigArtifactService {

    private static final Set<String> CONFIG_TYPES = Set.of(ArtifactKind.CONFIG.name(), ArtifactKind.REPAIR_CONFIG.name());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArtifactService artifactService;

    public ConfigArtifactServiceImpl(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Override
    public ConfigResponse getConfig(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new InvalidInputException("configId is required");
        }
        ArtifactRefDto artifact = artifactService.getArtifact(configId);
        if (!CONFIG_TYPES.contains(artifact.getType())) {
            throw new InvalidInputException("configId must reference a CONFIG or REPAIR_CONFIG artifact");
        }
        ArtifactContentDto content = artifactService.getArtifactContent(configId);
        maskSecrets(content);
        return ConfigResponse.builder()
                .artifact(artifact)
                .content(content)
                .status("READY")
                .build();
    }

    /** API 回显脱敏；执行链路直接读取 artifact 原文，不受这里影响。 */
    private void maskSecrets(ArtifactContentDto content) {
        if (content == null || content.getContent() == null
                || (content.getArtifactFormat() != null
                && !"json".equalsIgnoreCase(content.getArtifactFormat()))) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(content.getContent());
            if (root == null || root.isValueNode()) {
                return;
            }
            maskNode(root);
            content.setContent(MAPPER.writeValueAsString(root));
        } catch (Exception ignored) {
            // 非 JSON 内容保持原样，避免脱敏失败把配置读接口变成 500
        }
    }

    private void maskNode(JsonNode node) {
        if (node instanceof ObjectNode) {
            ObjectNode object = (ObjectNode) node;
            java.util.List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (DatasourceConnectionPolicy.isSensitivePropertyKey(name)) {
                    object.put(name, "******");
                } else if ("properties".equals(name) && value != null && value.isTextual()) {
                    object.put(name, maskProperties(value.asText()));
                } else {
                    maskNode(value);
                }
            }
        } else if (node instanceof ArrayNode) {
            ((ArrayNode) node).forEach(this::maskNode);
        }
    }

    private String maskProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            return properties;
        }
        java.util.List<String> masked = new java.util.ArrayList<>();
        for (String pair : properties.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
            if (DatasourceConnectionPolicy.isSensitivePropertyKey(key)) {
                masked.add(key + "=******");
            } else {
                masked.add(pair);
            }
        }
        return String.join("&", masked);
    }
}
