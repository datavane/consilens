package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * References to the source and target configurations resolved by a worker.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterComparisonDescription implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceConfigRef;

    private String targetConfigRef;
}
