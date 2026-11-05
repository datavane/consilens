package com.consilens.cluster.api;

import java.time.Duration;
import java.util.Locale;

/**
 * Submits a portable, redacted comparison description to one cluster backend.
 */
public interface ClusterSubmitter {

    /**
     * Stable platform identifier ({@code yarn}, {@code kubernetes}, {@code local})
     * used for ServiceLoader-based discovery. The default derives the identifier
     * from the implementation class name by stripping the {@code ClusterSubmitter}
     * suffix and lower-casing it ({@code KubernetesClusterSubmitter} becomes
     * {@code kubernetes}); implementations may override it with a constant.
     */
    default String platform() {
        String suffix = "ClusterSubmitter";
        String simpleName = getClass().getSimpleName();
        String base = simpleName.endsWith(suffix)
                ? simpleName.substring(0, simpleName.length() - suffix.length())
                : simpleName;
        return base.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the descriptor must exist as a local file before submission
     * (the YARN client stages it; Kubernetes ships it as a ConfigMap).
     */
    default boolean requiresDescriptorFile() {
        return false;
    }

    /**
     * Whether database passwords are replaced by environment placeholders in
     * the descriptor (Kubernetes uses Secret-backed env variables; YARN stages
     * the descriptor with credentials in place and relies on staging ACLs).
     */
    default boolean descriptorRedactsPasswords() {
        return false;
    }

    /**
     * Builds the platform-specific submission request: execution mode,
     * descriptor reference and the platform submission spec. Submitters that
     * only consume an already-built request (for example the local runtime)
     * may leave the default, which rejects request construction.
     */
    default ClusterSubmitRequest buildRequest(ClusterSubmissionContext context) {
        throw new UnsupportedOperationException(
                "cluster submitter does not build requests: " + getClass().getName());
    }

    ClusterSubmission submit(ClusterSubmitRequest request);

    /**
     * Waits until the submitted application reaches a terminal state, mirroring
     * spark-submit's default behaviour of blocking until the application
     * finishes and reporting its final status. Returns null when the submitter
     * cannot observe completion (for example local or simulated runtimes), in
     * which case the caller falls back to the submission acknowledgement.
     */
    default ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        return null;
    }

    /**
     * Requests termination of the submitted application (YARN kill / Kubernetes
     * Job deletion). Implementations must be idempotent; a no-op default keeps
     * submitters without kill support safe to call.
     */
    default void kill(ClusterSubmission submission) {
    }

    /**
     * Best-effort sweep of stale applications from a previous attempt of the
     * same logical submission (recovery restart), locating them through the
     * same namespace / job name / run tag that {@link #submit(ClusterSubmitRequest)}
     * would use. The sweep must not fail the new submission; a no-op for
     * submitters without the capability.
     */
    default void killStale(ClusterSubmitRequest request) {
    }
}
