package com.consilens.core.lifecycle;

import com.consilens.connector.api.model.TablePath;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context for a diff task, propagated throughout the DiffLifecycle.
 */
@Getter
@Builder
public class DiffContext {

    @Builder.Default
    private final String taskId = UUID.randomUUID().toString();

    @Builder.Default
    private final Instant startTime = Instant.now();

    private final TablePath sourceTablePath;

    private final TablePath targetTablePath;

    private final String strategy;

    private final String algorithm;

    /** Source column names (key + compare columns), used by table-format sinks to build the wide-table DDL. */
    private final List<String> sourceColumnNames;

    /** Target column names (key + compare columns), used by table-format sinks to build the wide-table DDL. */
    private final List<String> targetColumnNames;

    @Builder.Default
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
