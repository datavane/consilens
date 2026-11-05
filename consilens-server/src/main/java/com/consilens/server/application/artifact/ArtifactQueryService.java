package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.DiffPageDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.domain.enums.ArtifactKind;

import java.time.Instant;
import java.util.Collection;

public interface ArtifactQueryService {

    PageResponse<ArtifactListDto> listArtifacts(int page,
                                                int pageSize,
                                                Collection<ArtifactKind> kinds,
                                                String keyword,
                                                Instant startTime,
                                                Instant endTime,
                                                String traceId);

    /**
     * 分页读取差异记录（jsonl 文件按行偏移；旧 json 格式降级切片）。
     * operation 非空时按操作类型过滤（mismatch / source_missing / target_missing）。
     */
    DiffPageDto listDifferences(String artifactId, long offset, int limit, String operation, String traceId);
}
