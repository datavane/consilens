package com.consilens.cli.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of comparing two data contracts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDiff {

    private String sourceName;
    private String targetName;
    private boolean identical;
    private List<Change> changes;

    public int columnCount() {
        return changes == null ? 0 : changes.size();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {

        private ChangeKind kind;
        private String column;
        private String sourceValue;
        private String targetValue;
    }

    public enum ChangeKind {
        COLUMN_ADDED,
        COLUMN_REMOVED,
        TYPE_CHANGED,
        REQUIRED_CHANGED,
        UNIQUE_CHANGED,
        PRIMARY_KEY_CHANGED
    }
}
