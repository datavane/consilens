package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.model.TaskDefinitionPage;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface TaskDefinitionRepository {

    TaskDefinitionRecord save(TaskDefinitionRecord record);

    Optional<TaskDefinitionRecord> findById(Long id);

    Optional<TaskDefinitionRecord> findByName(String name);

    TaskDefinitionPage listPage(int page, int pageSize, String keyword, Boolean enabled);

    void deleteById(Long id);

    /** Mark last_run_at after an instance is submitted for this definition. */
    void updateLastRunAt(Long id, Instant lastRunAt);

    Map<Long, String> findNamesByIds(Collection<Long> ids);
}
