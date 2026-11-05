package com.consilens.core.diff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Reproducibility fingerprint for one comparison run.
 *
 * <p>Only safe configuration is hashed; connection credentials are never included.
 * Snapshot maps stay nullable because the engine does not implement a generic
 * database snapshot; a source-specific version id may be recorded when available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunFingerprint {

    private String configHash;

    private String profileHash;

    private String engineVersion;

    private Map<String, Object> sourceSnapshot;

    private Map<String, Object> targetSnapshot;

    private String runFingerprint;
}
