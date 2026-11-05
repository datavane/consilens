package com.consilens.server.infrastructure.db.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.consilens.server.infrastructure.db.entity.ArtifactEntity;

import java.util.List;

public interface ArtifactPersistenceService extends IService<ArtifactEntity> {

    List<ArtifactEntity> listByTaskId(Long taskId);
}
