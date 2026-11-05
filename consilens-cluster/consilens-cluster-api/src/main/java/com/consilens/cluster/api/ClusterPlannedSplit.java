package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * A Coordinator-planned split assigned to a submitted comparison application.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterPlannedSplit implements Serializable {

    private static final long serialVersionUID = 1L;

    private String submissionId;

    private ClusterKeyRangeSplit keyRangeSplit;

    public void validate() {
        if (submissionId == null || submissionId.trim().isEmpty()) {
            throw new IllegalArgumentException("submissionId is required for planned split");
        }
        if (keyRangeSplit == null) {
            throw new IllegalArgumentException("keyRangeSplit is required for planned split");
        }
        keyRangeSplit.toKeyRangeSplit();
    }
}
