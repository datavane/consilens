package com.consilens.core.diff;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a reproducible fingerprint from the safe parts of a compare request.
 * Credentials (the connector {@code connection} map) are never serialized.
 */
public final class RunFingerprintFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RunFingerprintFactory() {
    }

    public static RunFingerprint create(CompareRequest request, String engineVersion) {
        String version = engineVersion == null || engineVersion.isBlank() ? "unknown" : engineVersion;
        String configJson = write(safeConfig(request));
        String profileJson = request == null || request.getNormalizationSpec() == null
                ? "none"
                : write(request.getNormalizationSpec());

        String configHash = "sha256:" + sha256(configJson);
        String profileHash = "sha256:" + sha256(profileJson);
        String runFingerprint = "sha256:" + sha256(configHash + "|" + profileHash + "|" + version);

        return RunFingerprint.builder()
                .configHash(configHash)
                .profileHash(profileHash)
                .engineVersion(version)
                .runFingerprint(runFingerprint)
                .build();
    }

    private static Map<String, Object> safeConfig(CompareRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (request == null) {
            return config;
        }
        config.put("source", endpoint(request.getSource()));
        config.put("target", endpoint(request.getTarget()));
        config.put("sourceKeySpec", request.getSourceKeySpec());
        config.put("targetKeySpec", request.getTargetKeySpec());
        config.put("sourceComparisons", request.getSourceComparisons());
        config.put("targetComparisons", request.getTargetComparisons());
        config.put("sourceFilter", request.getSourceFilter());
        config.put("targetFilter", request.getTargetFilter());
        config.put("normalizationSpec", request.getNormalizationSpec());
        config.put("strategyPreference", request.getStrategyPreference());
        config.put("executionOptions", request.getExecutionOptions());
        return config;
    }

    private static Map<String, Object> endpoint(ConnectorConfig config) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        if (config == null) {
            return endpoint;
        }
        endpoint.put("type", config.getType());
        endpoint.put("name", config.getName());
        endpoint.put("resource", resource(config.getResource()));
        endpoint.put("readOptions", readOptions(config.getReadOptions()));
        return endpoint;
    }

    private static Map<String, Object> resource(ResourceLocator resource) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (resource == null) {
            return result;
        }
        result.put("type", resource.getType());
        result.put("name", resource.getName());
        result.put("path", resource.getPath());
        result.put("options", resource.getOptions());
        return result;
    }

    private static Map<String, Object> readOptions(ReadOptions options) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (options == null) {
            return result;
        }
        result.put("consistency", options.getConsistency());
        result.put("batchSize", options.getBatchSize());
        result.put("fetchSize", options.getFetchSize());
        return result;
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
