package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

/**
 * A plan for repairing data inconsistencies.
 */
@Data
@Builder
public class RepairPlan {

    /**
     * Which database side this plan targets.
     */
    public enum TargetSide {
        SOURCE, TARGET
    }

    /**
     * An individual SQL repair statement.
     */
    @Data
    @Builder
    public static class RepairStatement {
        private String sql;
        private String operation;
        private String description;
        private int affectedRows;
    }

    private TargetSide targetSide;

    @Singular
    private List<RepairStatement> statements;

    private String summary;

    private int totalAffectedRows;

    @Singular
    private List<String> warnings;
}
