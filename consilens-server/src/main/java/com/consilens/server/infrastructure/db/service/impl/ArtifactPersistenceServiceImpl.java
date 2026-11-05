package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.infrastructure.db.entity.ArtifactEntity;
import com.consilens.server.infrastructure.db.mapper.ArtifactMapper;
import com.consilens.server.infrastructure.db.service.ArtifactPersistenceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ArtifactPersistenceServiceImpl
        extends ServiceImpl<ArtifactMapper, ArtifactEntity>
        implements ArtifactPersistenceService {

    @Override
    public List<ArtifactEntity> listByTaskId(Long taskId) {
        return list(new QueryWrapper<ArtifactEntity>().lambda()
                .eq(ArtifactEntity::getTaskId, taskId)
                .orderByDesc(ArtifactEntity::getCreatedAt));
    }
}
