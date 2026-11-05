package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.domain.enums.TaskCommandStatus;
import com.consilens.server.infrastructure.db.entity.TaskCommandEntity;
import com.consilens.server.infrastructure.db.mapper.TaskCommandMapper;
import com.consilens.server.infrastructure.db.service.TaskCommandService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskCommandServiceImpl
        extends ServiceImpl<TaskCommandMapper, TaskCommandEntity>
        implements TaskCommandService {

    @Override
    public TaskCommandEntity getStartCommand(int totalSlot, int currentSlot, LocalDateTime now) {
        return baseMapper.getStartCommand(totalSlot, currentSlot, now);
    }

    @Override
    public boolean claim(Long id, String executeNodeKey, LocalDateTime lockTime, LocalDateTime lockUntil) {
        return baseMapper.claim(id, executeNodeKey, lockTime, lockUntil) > 0;
    }

    @Override
    public boolean markDone(Long id, LocalDateTime now) {
        return update(new UpdateWrapper<TaskCommandEntity>().lambda()
                .set(TaskCommandEntity::getStatus, TaskCommandStatus.DONE)
                .set(TaskCommandEntity::getLockUntil, null)
                .set(TaskCommandEntity::getUpdatedAt, now)
                .eq(TaskCommandEntity::getId, id)
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED));
    }

    @Override
    public boolean release(Long id, LocalDateTime now) {
        return update(new UpdateWrapper<TaskCommandEntity>().lambda()
                .set(TaskCommandEntity::getStatus, TaskCommandStatus.RELEASED)
                .set(TaskCommandEntity::getExecuteNodeKey, null)
                .set(TaskCommandEntity::getLockTime, null)
                .set(TaskCommandEntity::getLockUntil, null)
                .set(TaskCommandEntity::getUpdatedAt, now)
                .eq(TaskCommandEntity::getId, id)
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED));
    }

    @Override
    public boolean resetClaim(Long id, LocalDateTime now) {
        return update(new UpdateWrapper<TaskCommandEntity>().lambda()
                .set(TaskCommandEntity::getStatus, TaskCommandStatus.PENDING)
                .set(TaskCommandEntity::getExecuteNodeKey, null)
                .set(TaskCommandEntity::getLockTime, null)
                .set(TaskCommandEntity::getLockUntil, null)
                .set(TaskCommandEntity::getUpdatedAt, now)
                .eq(TaskCommandEntity::getId, id)
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED));
    }

    @Override
    public boolean releaseClaimedByTaskId(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskCommandEntity>().lambda()
                .set(TaskCommandEntity::getStatus, TaskCommandStatus.RELEASED)
                .set(TaskCommandEntity::getExecuteNodeKey, null)
                .set(TaskCommandEntity::getLockTime, null)
                .set(TaskCommandEntity::getLockUntil, null)
                .set(TaskCommandEntity::getUpdatedAt, now)
                .eq(TaskCommandEntity::getTaskId, taskId)
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED));
    }

    @Override
    public boolean releaseOpenByTaskId(Long taskId, LocalDateTime now) {
        return update(new UpdateWrapper<TaskCommandEntity>().lambda()
                .set(TaskCommandEntity::getStatus, TaskCommandStatus.RELEASED)
                .set(TaskCommandEntity::getExecuteNodeKey, null)
                .set(TaskCommandEntity::getLockTime, null)
                .set(TaskCommandEntity::getLockUntil, null)
                .set(TaskCommandEntity::getUpdatedAt, now)
                .eq(TaskCommandEntity::getTaskId, taskId)
                .in(TaskCommandEntity::getStatus, TaskCommandStatus.PENDING, TaskCommandStatus.CLAIMED));
    }

    @Override
    public List<TaskCommandEntity> listExpiredClaims(LocalDateTime now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return list(new QueryWrapper<TaskCommandEntity>().lambda()
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED)
                .lt(TaskCommandEntity::getLockUntil, now)
                .orderByAsc(TaskCommandEntity::getUpdatedAt)
                .last("LIMIT " + limit));
    }

    @Override
    public List<TaskCommandEntity> listClaimedByExecuteNode(String executeNodeKey, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return list(new QueryWrapper<TaskCommandEntity>().lambda()
                .eq(TaskCommandEntity::getStatus, TaskCommandStatus.CLAIMED)
                .eq(TaskCommandEntity::getExecuteNodeKey, executeNodeKey)
                .orderByAsc(TaskCommandEntity::getUpdatedAt)
                .last("LIMIT " + limit));
    }
}
