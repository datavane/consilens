package com.consilens.server.infrastructure.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consilens.server.infrastructure.db.entity.ServerNodeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ServerNodeMapper extends BaseMapper<ServerNodeEntity> {
}
