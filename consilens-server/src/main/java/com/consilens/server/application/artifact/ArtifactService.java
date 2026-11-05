package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.TaskExecutionContext;

import java.util.Map;

public interface ArtifactService {

    ArtifactRefDto getArtifact(String artifactId);

    ArtifactContentDto getArtifactContent(String artifactId);

    ArtifactRefDto writeArtifact(TaskExecutionContext context,
                                 ArtifactKind artifactKind,
                                 String format,
                                 Object content,
                                 Map<String, Object> metadata);

    /**
     * 在 RUN_RESULT artifact 上回填统计与差异文件元数据（统计数据入库，差异明细在独立 jsonl 文件）。
     */
    ArtifactRefDto updateRunResultMeta(String artifactId,
                                       Map<String, Object> statistics,
                                       boolean truncated,
                                       long rows,
                                       String differencesUri);
}
