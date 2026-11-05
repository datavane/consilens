package com.consilens.server.domain.repository;

import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactPage;
import com.consilens.server.domain.model.ArtifactRecord;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ArtifactRepository {

    Optional<ArtifactRecord> findById(String artifactId);

    List<ArtifactRecord> listByTaskId(Long taskId);

    ArtifactRecord save(ArtifactRecord artifactRecord);

    /** 更新已有记录（用于回填统计数据等元数据）。 */
    ArtifactRecord update(ArtifactRecord artifactRecord);

    ArtifactPage listArtifactPage(int page,
                                  int pageSize,
                                  Collection<ArtifactKind> kinds,
                                  String artifactIdLike,
                                  Instant startTime,
                                  Instant endTime);

    List<ArtifactRecord> listCreatedSince(Instant start);
}
