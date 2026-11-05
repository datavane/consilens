package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.TaskCommandRecord;

import java.time.Instant;
import java.util.List;

public interface TaskCommandRepository {

    TaskCommandRecord save(TaskCommandRecord taskCommandRecord);

    TaskCommandRecord getStartCommand(int totalSlot, int currentSlot, Instant now);

    boolean claim(Long id, String executeNodeKey, Instant lockTime, Instant lockUntil);

    void markDone(Long id, Instant now);

    boolean release(Long id, Instant now);

    boolean resetClaim(Long id, Instant now);

    boolean releaseClaimedByTaskId(Long taskId, Instant now);

    boolean releaseOpenByTaskId(Long taskId, Instant now);

    List<TaskCommandRecord> listExpiredClaims(Instant now, int limit);

    List<TaskCommandRecord> listClaimedByExecuteNode(String executeNodeKey, int limit);
}
