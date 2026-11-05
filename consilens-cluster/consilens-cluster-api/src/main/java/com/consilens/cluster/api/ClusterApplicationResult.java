package com.consilens.cluster.api;

import lombok.Getter;

/**
 * Terminal outcome of one submitted cluster application, reported by the
 * backend after the client waited for completion.
 */
@Getter
public class ClusterApplicationResult {

    private final String applicationId;
    private final String finalStatus;
    private final String trackingUrl;
    private final String diagnostics;

    public ClusterApplicationResult(String applicationId, String finalStatus,
                                    String trackingUrl, String diagnostics) {
        this.applicationId = applicationId;
        this.finalStatus = finalStatus;
        this.trackingUrl = trackingUrl;
        this.diagnostics = diagnostics;
    }

    public boolean succeeded() {
        return "SUCCEEDED".equalsIgnoreCase(finalStatus);
    }
}
