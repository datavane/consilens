package com.consilens.cluster.runtime;

import com.consilens.cluster.api.ClusterSubmitRequest;

/**
 * Resolves an in-process local task after the portable submission envelope is accepted.
 */
@FunctionalInterface
public interface LocalSubmissionTaskProvider {

    LocalSimulationRequest resolve(ClusterSubmitRequest request) throws Exception;
}
