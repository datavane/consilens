package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.DiffPageDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.application.artifact.ArtifactQueryService;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/artifacts")
@RequiredArgsConstructor
@Validated
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactQueryService artifactQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ArtifactListDto>>> listArtifacts(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) List<ArtifactKind> artifactType,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        PageResponse<ArtifactListDto> result = artifactQueryService.listArtifacts(page,
                pageSize,
                artifactType,
                keyword,
                startTime,
                endTime,
                traceId);
        return ResponseEntity.ok(ApiResponse.success(result, traceId));
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<ApiResponse<ArtifactRefDto>> getArtifact(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String artifactId,
                                                                   HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(artifactService.getArtifact(artifactId), traceId));
    }

    @GetMapping("/{artifactId}/differences")
    public ResponseEntity<ApiResponse<DiffPageDto>> listDifferences(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String artifactId,
            @RequestParam(defaultValue = "0") @Min(0) long offset,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(required = false) String operation,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(
                artifactQueryService.listDifferences(artifactId, offset, limit, operation, traceId), traceId));
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<ApiResponse<ArtifactContentDto>> getArtifactContent(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String artifactId,
                                                                              HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(artifactService.getArtifactContent(artifactId), traceId));
    }
}
