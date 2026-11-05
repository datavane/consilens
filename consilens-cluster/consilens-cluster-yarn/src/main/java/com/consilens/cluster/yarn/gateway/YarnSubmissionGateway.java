package com.consilens.cluster.yarn.gateway;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;

import java.net.URI;

/**
 * Boundary around the Hadoop {@code YarnClient} so submission mapping can be tested
 * without a live ResourceManager.
 */
public interface YarnSubmissionGateway extends AutoCloseable {

    ApplicationSubmissionContext createSubmissionContext();

    LocalResource createLocalResource(URI resourceUri, LocalResourceType type);

    URI stageLocalFile(URI localUri, URI stagingBase);

    ApplicationId submit(ApplicationSubmissionContext context);

    /**
     * Default staging directory derived from the submitting user's HDFS home
     * ({@code <home>/.consilens/staging}), mirroring how spark-submit stages
     * under {@code spark.yarn.stagingDir} in the user's home without explicit
     * configuration. Returns null when unknown.
     */
    default String defaultStagingBase() {
        return null;
    }

    /**
     * Deletes the per-application staging directory (best effort). Used when
     * submission fails after staging so descriptors/credentials do not linger in HDFS.
     */
    default void deleteStagingDirectory(URI stagingUri) {
    }

    /**
     * Current state name of the application, e.g. {@code ACCEPTED},
     * {@code RUNNING}, {@code FINISHED}. Empty when the application is unknown.
     */
    default java.util.Optional<String> applicationState(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Final application status, e.g. {@code SUCCEEDED}; empty until the
     * application reaches a terminal state or when unknown.
     */
    default java.util.Optional<String> applicationFinalStatus(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Tracking URL for the running application; empty when unknown.
     */
    default java.util.Optional<String> trackingUrl(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Diagnostics reported by the ApplicationMaster via
     * {@code unregisterApplicationMaster}. The AM puts the coordinator's summary
     * JSON here so the submitter (and the server behind it) can recover the
     * comparison statistics without reading container logs. Empty when unknown.
     */
    default java.util.Optional<String> applicationDiagnostics(String applicationId) {
        return java.util.Optional.empty();
    }

    /**
     * Requests termination of the application; idempotent.
     */
    default void kill(String applicationId) {
    }

    /**
     * Kills every running application carrying the given tag; returns the
     * number of kill requests issued. Used for stale-run cleanup after
     * recovery restarts.
     */
    default int killByTag(String tag) {
        return 0;
    }

    @Override
    void close();
}
