package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.TaskPage;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.enums.TaskStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TaskRepository {

    Optional<TaskInstanceRecord> findById(Long id);

    /**
     * 按引用解析实例：纯数字视为数据库雪花 id（Long），否则视为 instance_key。
     * 兼容列表用 id、旧链接用 inst_xxx 两种取数方式。
     */
    default Optional<TaskInstanceRecord> resolveByRef(String ref) {
        if (ref != null && ref.matches("\\d+")) {
            try {
                return findById(Long.valueOf(ref));
            } catch (NumberFormatException ignored) {
                // fall through to key lookup
            }
        }
        return findByTaskKey(ref);
    }

    Optional<TaskInstanceRecord> findByTaskKey(String instanceKey);

    Optional<TaskInstanceRecord> findBySerialNo(String serialNo);

    Optional<TaskInstanceRecord> lockBySerialNo(String serialNo);

    TaskInstanceRecord save(TaskInstanceRecord taskRecord);

    boolean updateClaimed(Long taskId, String executeNodeKey, Instant now);

    boolean updateRunning(Long taskId, String executeNodeKey, Instant now);

    boolean renewRunning(Long taskId, Instant now);

    boolean updateSuccess(Long taskId, String artifactId, Instant now);

    boolean updateFailure(Long taskId, String errorCode, String errorMessage, Instant now);

    boolean updateRetryable(Long taskId, String errorCode, String errorMessage, Instant now);

    boolean updateFailureFromStatuses(Long taskId,
                                      Collection<TaskStatus> statuses,
                                      String errorCode,
                                      String errorMessage,
                                      Instant now);

    boolean updateRetryableFromStatuses(Long taskId,
                                        Collection<TaskStatus> statuses,
                                        String errorCode,
                                        String errorMessage,
                                        Instant now);

    boolean releaseClaimed(Long taskId, Instant now);

    boolean resetForRetry(Long taskId, String traceId, Instant now);

    boolean cancel(Long taskId, String traceId, Instant now);

    boolean requestCancellation(Long taskId, String traceId, Instant now);

    boolean confirmCancellation(Long taskId, Instant now);

    List<TaskInstanceRecord> listByExecuteNodeAndStatuses(String executeNodeKey, Collection<TaskStatus> statuses, int limit);

    List<TaskInstanceRecord> listByStatusesExcludingExecuteNodes(Collection<TaskStatus> statuses,
                                                         Set<String> executeNodeKeys,
                                                         int limit);

    List<TaskInstanceRecord> listStaleRunning(Instant before, int limit);

    /** 指定状态的 stale 任务（用于回收已请求取消但执行线程已失联的任务）。 */
    List<TaskInstanceRecord> listStaleByStatus(TaskStatus status, Instant before, int limit);

    TaskPage listTaskPage(int page,
                          int pageSize,
                          Collection<TaskStatus> statuses,
                          String serialNoLike,
                          String executeNodeKey,
                          Instant startTime,
                          Instant endTime,
                          Long definitionId);

    long countByStatuses(Collection<TaskStatus> statuses);

    long countSubmittedSince(Instant start, Instant end);

    List<TaskInstanceRecord> listSubmittedSince(Instant start);

    List<TaskInstanceRecord> listByDefinitionId(Long definitionId, int limit);
}
