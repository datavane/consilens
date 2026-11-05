package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.DiffPageDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.ArtifactIntegrityException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.consilens.server.infrastructure.storage.StoredArtifactContent;
import com.consilens.server.support.hash.Sha256Support;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ArtifactServiceImpl implements ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ObjectMapper objectMapper;

    public ArtifactServiceImpl(ArtifactRepository artifactRepository,
                                  ArtifactContentStore artifactContentStore,
                                  ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public ArtifactRefDto getArtifact(String artifactId) {
        ArtifactRecord record = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found: " + artifactId));
        return toRef(record);
    }

    @Override
    public ArtifactContentDto getArtifactContent(String artifactId) {
        ArtifactRecord record = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found: " + artifactId));
        byte[] content = artifactContentStore.read(record.getStorageUri());
        verifyChecksum(record, content);
        return ArtifactContentDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .artifactFormat(record.getArtifactFormat())
                .content(new String(content, StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public ArtifactRefDto writeArtifact(TaskExecutionContext context,
                                        ArtifactKind artifactKind,
                                        String format,
                                        Object content,
                                        Map<String, Object> metadata) {
        String artifactId = "artifact_" + UUID.randomUUID();
        String artifactFormat = format == null || format.isBlank() ? "json" : format;
        byte[] bytes = serializeContent(content, artifactFormat);
        StoredArtifactContent stored = artifactContentStore.write(artifactId, artifactKind, artifactFormat, bytes);
        Map<String, Object> mergedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        if (context != null) {
            putIfPresent(mergedMetadata, "traceId", context.getTraceId());
            putIfPresent(mergedMetadata, "taskId", context.getInstanceKey());
            putIfPresent(mergedMetadata, "nodeKey", context.getNodeKey());
        }
        ArtifactRecord saved = artifactRepository.save(ArtifactRecord.builder()
                .id(artifactId)
                .taskId(context != null ? context.getTaskId() : null)
                .traceId(context != null ? context.getTraceId() : null)
                .artifactType(artifactKind)
                .artifactFormat(artifactFormat)
                .storageType(stored.getStorageType())
                .storageUri(stored.getStorageUri())
                .sha256(stored.getSha256())
                .metadataJson(toJson(mergedMetadata))
                .createdAt(Instant.now())
                .build());
        return toRef(saved);
    }

    @Override
    public ArtifactRefDto updateRunResultMeta(String artifactId,
                                              Map<String, Object> statistics,
                                              boolean truncated,
                                              long rows,
                                              String differencesUri) {
        ArtifactRecord record = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new com.consilens.server.domain.exception.ResourceNotFoundException(
                        "Artifact not found: " + artifactId));
        // statistics 为 null/空表示本次运行没有可比对的统计（dryRun、集群未回传统计），
        // 不能落成差异数 0，否则报告链路会把它推断成 100% 匹配。
        boolean statisticsAvailable = statistics != null && !statistics.isEmpty();
        record.setStatisticsJson(statisticsAvailable ? toJson(statistics) : null);
        Object total = statisticsAvailable ? statistics.get("totalDifferences") : null;
        record.setDifferenceCount(total instanceof Number ? ((Number) total).longValue() : null);
        record.setDifferenceTruncated(truncated);
        record.setDifferenceRows(Math.min(rows, Integer.MAX_VALUE));
        record.setDifferencesUri(differencesUri);
        return toRef(artifactRepository.update(record));
    }

    private ArtifactRefDto toRef(ArtifactRecord record) {
        return ArtifactRefDto.builder()
                .id(record.getId())
                .type(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .metadata(readMetadata(record.getMetadataJson()))
                .build();
    }

    private byte[] serializeContent(Object content, String format) {
        try {
            if (content instanceof byte[]) {
                return (byte[]) content;
            }
            if (content instanceof String) {
                return ((String) content).getBytes(StandardCharsets.UTF_8);
            }
            if ("json".equalsIgnoreCase(format) || "ndjson".equalsIgnoreCase(format)) {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content);
            }
            return String.valueOf(content).getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize artifact content", exception);
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize artifact metadata", exception);
        }
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.putIfAbsent(key, value);
        }
    }

    private void verifyChecksum(ArtifactRecord record, byte[] content) {
        if (record.getSha256() == null || record.getSha256().isBlank()) {
            return;
        }
        String actual = Sha256Support.hex(content);
        if (!record.getSha256().equalsIgnoreCase(actual)) {
            throw new ArtifactIntegrityException("Artifact content checksum mismatch: " + record.getId());
        }
    }
}
