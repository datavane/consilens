package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.DiffPageDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactPage;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ArtifactQueryServiceImpl implements ArtifactQueryService {

    /**
     * 列表场景下 RUN_RESULT content 超过该大小仅回传 size，不解析 differenceCount。
     */
    private static final long MAX_SUMMARY_CONTENT_BYTES = 2L * 1024 * 1024;

    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ObjectMapper objectMapper;

    public ArtifactQueryServiceImpl(ArtifactRepository artifactRepository,
                                       ArtifactContentStore artifactContentStore,
                                       ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<ArtifactListDto> listArtifacts(int page,
                                                       int pageSize,
                                                       Collection<ArtifactKind> kinds,
                                                       String keyword,
                                                       Instant startTime,
                                                       Instant endTime,
                                                       String traceId) {
        ArtifactPage artifactPage = artifactRepository.listArtifactPage(page,
                pageSize,
                kinds,
                keyword,
                startTime,
                endTime);
        List<ArtifactListDto> items = artifactPage.getItems().stream()
                .map(this::toListDto)
                .collect(Collectors.toList());
        return PageResponse.<ArtifactListDto>builder()
                .total(artifactPage.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(items)
                .build();
    }

    private ArtifactListDto toListDto(ArtifactRecord record) {
        if (record.getArtifactType() == ArtifactKind.RUN_RESULT) {
            return toRunResultListDto(record);
        }
        return ArtifactListDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .sizeBytes(sizeOf(record))
                .createdAt(record.getCreatedAt())
                .metadata(readMetadata(record.getMetadataJson()))
                .build();
    }

    /**
     * For RUN_RESULT artifacts, read the content once and derive both the byte size
     * and the total difference count (statistics.totalDifferences) to avoid double IO.
     */
    private ArtifactListDto toRunResultListDto(ArtifactRecord record) {
        // 新格式：统计/行数/截断/文件 URI 均来自 DB 字段，免文件 IO
        Long differenceCount = record.getDifferenceCount();
        if (differenceCount == null) {
            // 旧格式回退：读 content 解析（带阈值保护）
            RunResultMeta meta = readRunResultMeta(record);
            differenceCount = meta != null ? meta.differenceCount : null;
        }
        return ArtifactListDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .sizeBytes(sizeOf(record))
                .createdAt(record.getCreatedAt())
                .metadata(readMetadata(record.getMetadataJson()))
                .differenceCount(differenceCount)
                .differenceRows(record.getDifferenceRows())
                .differenceTruncated(record.getDifferenceTruncated())
                // 存储 URI 是服务端本地路径，不能回传给客户端；只暴露可用性
                .differenceDetailsAvailable(record.getDifferencesUri() != null
                        && !record.getDifferencesUri().isBlank())
                .build();
    }

    private RunResultMeta readRunResultMeta(ArtifactRecord record) {
        try {
            if (record.getStorageUri() == null || record.getStorageUri().isBlank()) {
                return null;
            }
            long sizeBytes = artifactContentStore.size(record.getStorageUri());
            // 列表场景避免全量读取超大 content：超过阈值仅回传大小（differenceCount 可空降级）
            if (sizeBytes > MAX_SUMMARY_CONTENT_BYTES) {
                return new RunResultMeta(sizeBytes, null);
            }
            byte[] bytes = artifactContentStore.read(record.getStorageUri());
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null) {
                return new RunResultMeta(sizeBytes, null);
            }
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                return new RunResultMeta(sizeBytes, null);
            }
            JsonNode statistics = root.get("statistics");
            Long differenceCount = null;
            if (statistics != null && statistics.isObject()
                    && statistics.path("totalDifferences").isNumber()) {
                differenceCount = statistics.path("totalDifferences").asLong();
            } else if (root.path("differenceCount").isNumber()) {
                differenceCount = root.path("differenceCount").asLong();
            }
            return new RunResultMeta(sizeBytes, differenceCount);
        } catch (Exception exception) {
            return null;
        }
    }

    private static final class RunResultMeta {
        private final long sizeBytes;
        private final Long differenceCount;

        private RunResultMeta(long sizeBytes, Long differenceCount) {
            this.sizeBytes = sizeBytes;
            this.differenceCount = differenceCount;
        }
    }

    private Long sizeOf(ArtifactRecord record) {
        try {
            if (record.getStorageUri() == null || record.getStorageUri().isBlank()) {
                return null;
            }
            return artifactContentStore.size(record.getStorageUri());
        } catch (Exception exception) {
            return null;
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
            return new LinkedHashMap<>();
        }
    }

    @Override
    public DiffPageDto listDifferences(String artifactId, long offset, int limit, String operation, String traceId) {
        ArtifactRecord record = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new com.consilens.server.domain.exception.ResourceNotFoundException(
                        "Artifact not found: " + artifactId));
        try {
            if (record.getDifferencesUri() != null && !record.getDifferencesUri().isBlank()) {
                return readJsonlPage(record, offset, limit, operation);
            }
            return readLegacyJsonPage(record, offset, limit, operation);
        } catch (java.nio.file.NoSuchFileException exception) {
            // 差异文件已被清理/缺失：降级为空页，避免 500
            return DiffPageDto.builder()
                    .artifactId(record.getId())
                    .total(record.getDifferenceRows() != null ? record.getDifferenceRows() : 0L)
                    .rows(0)
                    .truncated(Boolean.TRUE.equals(record.getDifferenceTruncated()))
                    .hasMore(false)
                    .items(java.util.Collections.emptyList())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read differences for " + artifactId, exception);
        }
    }

    /**
     * 新格式：jsonl 文件按行偏移分页（流式读取，不整文件加载）。
     * operation 非空时仅统计并返回该类型的记录，offset/limit 作用于过滤后的结果。
     *
     * <p>不做无谓的全文件扫描：
     * <ul>
     *   <li>不带 operation 时总数优先用工件元数据（{@code differenceRows}），取满一页就停；</li>
     *   <li>需要过滤时，先用一次字符串定位取 {@code operation} 字段，命中的行才做 JSON 解析
     *       （原来每行都完整反序列化，只为读一个字段）。</li>
     * </ul>
     */
    private DiffPageDto readJsonlPage(ArtifactRecord record, long offset, int limit, String operation) throws Exception {
        boolean filtered = operation != null && !operation.isBlank();
        Long knownTotal = filtered ? null : record.getDifferenceRows();
        boolean counting = knownTotal == null;
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        long matched = 0;
        long totalMatched = 0;
        boolean reachedEnd = true;
        try (BufferedReader reader = Files.newBufferedReader(
                java.nio.file.Paths.get(record.getDifferencesUri()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (filtered && !matchesOperation(operation, operationOf(line))) {
                    continue;
                }
                if (counting) {
                    totalMatched++;
                }
                if (matched >= offset && items.size() < limit) {
                    items.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                }
                matched++;
                if (!counting && items.size() >= limit) {
                    // 总数已知且本页已满：剩下的行与返回值无关
                    reachedEnd = false;
                    break;
                }
            }
        }
        long total = counting || reachedEnd ? matched : Math.max(knownTotal, matched);
        return DiffPageDto.builder()
                .artifactId(record.getId())
                .total(total)
                .rows(items.size())
                .truncated(Boolean.TRUE.equals(record.getDifferenceTruncated()))
                .hasMore(offset + items.size() < total)
                .items(items)
                .build();
    }

    /** 只取顶层 {@code operation} 字段，避免为了过滤把整行反序列化。 */
    private String operationOf(String line) {
        int index = line.indexOf("\"operation\"");
        if (index < 0) {
            return parseOperation(line);
        }
        int colon = line.indexOf(':', index + 11);
        if (colon < 0) {
            return parseOperation(line);
        }
        int quote = line.indexOf('"', colon + 1);
        if (quote < 0) {
            return parseOperation(line);
        }
        int end = line.indexOf('"', quote + 1);
        if (end < 0) {
            return parseOperation(line);
        }
        return line.substring(quote + 1, end);
    }

    private String parseOperation(String line) {
        try {
            Map<String, Object> row = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
            });
            return row.get("operation") == null ? null : String.valueOf(row.get("operation"));
        } catch (Exception exception) {
            return null;
        }
    }

    /** operation 多选过滤：逗号分隔（如 mismatch,source_missing），忽略大小写。 */
    private static boolean matchesOperation(String operation, String rowOperation) {
        String normalized = rowOperation == null ? "" : rowOperation.toLowerCase();
        for (String part : operation.split(",")) {
            if (part != null && part.trim().equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 旧格式（format=json 整包含差异）：从 content 的 differences 数组切片（≤1000 行）。 */
    private DiffPageDto readLegacyJsonPage(ArtifactRecord record, long offset, int limit, String operation) throws Exception {
        byte[] bytes = artifactContentStore.read(record.getStorageUri());
        JsonNode root = objectMapper.readTree(bytes);
        JsonNode differences = root.get("differences");
        java.util.List<Map<String, Object>> all = new java.util.ArrayList<>();
        if (differences != null && differences.isArray()) {
            for (JsonNode node : differences) {
                all.add(objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                }));
            }
        }
        if (operation != null && !operation.isBlank()) {
            all.removeIf(row -> !matchesOperation(operation, String.valueOf(row.get("operation"))));
        }
        java.util.List<Map<String, Object>> items = all.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
        return DiffPageDto.builder()
                .artifactId(record.getId())
                .total(all.size())
                .rows(items.size())
                .truncated(root.path("differenceSampleTruncated").asBoolean(false))
                .hasMore(offset + items.size() < all.size())
                .items(items)
                .build();
    }
}