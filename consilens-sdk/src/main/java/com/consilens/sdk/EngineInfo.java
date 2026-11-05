package com.consilens.sdk;

import lombok.Getter;

/**
 * Static engine information exposed to integrators.
 */
@Getter
public final class EngineInfo {

    /**
     * Stable SDK API contract version. Bumped only for breaking changes.
     */
    public static final String API_VERSION = "1.0";

    private final String name;
    private final String version;
    private final String apiVersion;

    private EngineInfo(String name, String version, String apiVersion) {
        this.name = name;
        this.version = version;
        this.apiVersion = apiVersion;
    }

    public static EngineInfo current() {
        String version = ConsilensEngine.class.getPackage() != null
                ? ConsilensEngine.class.getPackage().getImplementationVersion()
                : null;
        return new EngineInfo("consilens",
                version == null || version.isBlank() ? "0.1-SNAPSHOT" : version,
                API_VERSION);
    }
}
