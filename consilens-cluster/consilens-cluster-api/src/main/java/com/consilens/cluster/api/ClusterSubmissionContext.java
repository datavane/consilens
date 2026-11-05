package com.consilens.cluster.api;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-neutral inputs for {@link ClusterSubmitter#buildRequest(ClusterSubmissionContext)}.
 * The submitter owns the mapping to its execution mode, descriptor reference
 * and platform submission spec; this context only carries the descriptor and
 * the portable submission properties.
 */
public final class ClusterSubmissionContext {

    private final String submissionId;
    private final String descriptor;
    private final Path descriptorFile;
    private final Map<String, Object> properties;

    public ClusterSubmissionContext(String submissionId,
                                    String descriptor,
                                    Path descriptorFile,
                                    Map<String, Object> properties) {
        this.submissionId = submissionId;
        this.descriptor = descriptor;
        this.descriptorFile = descriptorFile;
        this.properties = properties == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public String getSubmissionId() {
        return submissionId;
    }

    /** Redacted comparison descriptor content (YAML), identical for every platform. */
    public String getDescriptor() {
        return descriptor;
    }

    /** Local descriptor file when {@link ClusterSubmitter#requiresDescriptorFile()} is true, else null. */
    public Path getDescriptorFile() {
        return descriptorFile;
    }

    /** Portable submission properties from the run request; never null. */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
