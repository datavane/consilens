package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.infrastructure.db.entity.ServerNodeEntity;
import com.consilens.server.infrastructure.db.mapper.ServerNodeMapper;
import com.consilens.server.infrastructure.db.service.ServerNodeService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ServerNodeServiceImpl extends ServiceImpl<ServerNodeMapper, ServerNodeEntity> implements ServerNodeService {

    @Override
    public ServerNodeEntity getByNodeKey(String nodeKey) {
        return getOne(new QueryWrapper<ServerNodeEntity>().lambda()
                .eq(ServerNodeEntity::getNodeKey, nodeKey), false);
    }

    @Override
    public List<ServerNodeEntity> listAllOrdered() {
        return list(new QueryWrapper<ServerNodeEntity>().lambda()
                .orderByAsc(ServerNodeEntity::getNodeKey));
    }

    @Override
    public List<ServerNodeEntity> listAliveSince(LocalDateTime cutoff) {
        return list(new QueryWrapper<ServerNodeEntity>().lambda()
                .ge(ServerNodeEntity::getHeartbeatTime, cutoff)
                .orderByAsc(ServerNodeEntity::getNodeKey));
    }

    @Override
    public int deleteStaleBefore(LocalDateTime cutoff) {
        return baseMapper.delete(new LambdaQueryWrapper<ServerNodeEntity>()
                .lt(ServerNodeEntity::getHeartbeatTime, cutoff));
    }

    @Override
    public ServerNodeEntity saveOrUpdateByNodeKey(ServerNodeEntity entity) {
        if (entity.getId() == null) {
            try {
                save(entity);
                return entity;
            } catch (DuplicateKeyException duplicateKeyException) {
                ServerNodeEntity existing = getByNodeKey(entity.getNodeKey());
                if (existing == null) {
                    throw duplicateKeyException;
                }
                entity.setId(existing.getId());
            }
        }
        updateById(entity);
        return entity;
    }
}
