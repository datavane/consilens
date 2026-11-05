package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactListDto {

    private String artifactId;
    private String artifactType;
    private String format;
    private Long sizeBytes;
    private Instant createdAt;
    private Map<String, Object> metadata;

    /** Total difference count for RUN_RESULT artifacts; null for other kinds. */
    private Long differenceCount;

    /** Rows written to the differences file; null for other kinds or legacy format. */
    private Long differenceRows;

    /** Whether the differences file was truncated at maxRows; null for other kinds. */
    private Boolean differenceTruncated;

    /**
     * Internal storage URI of the differences file. Never populated in API responses:
     * it exposes server filesystem layout and clients should use the artifact
     * differences endpoint instead. Kept for response-model compatibility.
     */
    private String differencesUri;

    /** Whether paginated diff details are available for this artifact. */
    private Boolean differenceDetailsAvailable;

    /** Parsed statistics (summary/column stats) for RUN_RESULT artifacts; null when absent. */
    private Map<String, Object> statistics;
}
