package com.consilens.server.infrastructure.db.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.consilens.server.infrastructure.db.entity.TaskCommandEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskCommandService extends IService<TaskCommandEntity> {

    TaskCommandEntity getStartCommand(int totalSlot, int currentSlot, LocalDateTime now);

    boolean claim(Long id, String executeNodeKey, LocalDateTime lockTime, LocalDateTime lockUntil);

    boolean markDone(Long id, LocalDateTime now);

    boolean release(Long id, LocalDateTime now);

    boolean resetClaim(Long id, LocalDateTime now);

    boolean releaseClaimedByTaskId(Long taskId, LocalDateTime now);

    boolean releaseOpenByTaskId(Long taskId, LocalDateTime now);

    List<TaskCommandEntity> listExpiredClaims(LocalDateTime now, int limit);

    List<TaskCommandEntity> listClaimedByExecuteNode(String executeNodeKey, int limit);
}
