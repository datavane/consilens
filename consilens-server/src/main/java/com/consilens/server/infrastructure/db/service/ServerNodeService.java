package com.consilens.server.infrastructure.db.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.consilens.server.infrastructure.db.entity.ServerNodeEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface ServerNodeService extends IService<ServerNodeEntity> {

    ServerNodeEntity getByNodeKey(String nodeKey);

    List<ServerNodeEntity> listAllOrdered();

    List<ServerNodeEntity> listAliveSince(LocalDateTime cutoff);

    int deleteStaleBefore(LocalDateTime cutoff);

    ServerNodeEntity saveOrUpdateByNodeKey(ServerNodeEntity entity);
}
