package com.consilens.server.infrastructure.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consilens.server.infrastructure.db.entity.TaskDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskDefinitionMapper extends BaseMapper<TaskDefinitionEntity> {
}
