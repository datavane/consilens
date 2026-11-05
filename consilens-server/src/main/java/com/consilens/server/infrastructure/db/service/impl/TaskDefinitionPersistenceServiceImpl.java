package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.infrastructure.db.entity.TaskDefinitionEntity;
import com.consilens.server.infrastructure.db.mapper.TaskDefinitionMapper;
import com.consilens.server.infrastructure.db.service.TaskDefinitionPersistenceService;
import org.springframework.stereotype.Service;

@Service
public class TaskDefinitionPersistenceServiceImpl extends ServiceImpl<TaskDefinitionMapper, TaskDefinitionEntity>
        implements TaskDefinitionPersistenceService {
}
