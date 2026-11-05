package com.consilens.core.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Credentials for opt-in database integration tests. Secrets must come from the
 * environment so they are never committed to the repository.
 */
public final class IntegrationCredentials {

    private IntegrationCredentials() {
    }

    public static String require(String environmentVariable) {
        String value = System.getenv(environmentVariable);
        assumeTrue(value != null && !value.isBlank(),
                "Set " + environmentVariable + " to run this database integration test");
        return value == null ? "" : value;
    }
}
