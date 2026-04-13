package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;

/**
 * Metadata about a LLM backend.
 */
@Data
@Builder
public class BackendInfo {

    /** Display name of the backend. */
    private String name;

    /** Model identifier being used. */
    private String model;

    /** Version of the backend or model. */
    private String version;

    /** Whether this backend supports function/tool calling. */
    private boolean supportsFunctionCalling;

    /** Whether this backend supports streaming responses. */
    private boolean supportsStreaming;
}
