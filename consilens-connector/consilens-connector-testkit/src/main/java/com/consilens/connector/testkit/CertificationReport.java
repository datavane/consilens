package com.consilens.connector.testkit;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of running the connector certification checks.
 */
public final class CertificationReport {

    @Getter
    private final String connectorType;

    @Getter
    private final String resource;

    private final List<Check> checks = new ArrayList<>();

    @Setter
    @Getter
    private long recordsRead;

    public CertificationReport(String connectorType, String resource) {
        this.connectorType = connectorType;
        this.resource = resource;
    }

    public List<Check> getChecks() {
        return List.copyOf(checks);
    }

    public void add(String name, boolean passed, String message) {
        checks.add(new Check(name, passed, message));
    }

    public boolean isPassed() {
        return !checks.isEmpty() && checks.stream().allMatch(Check::isPassed);
    }

    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        for (Check check : checks) {
            if (!check.isPassed()) {
                failures.add(check.getName() + ": " + check.getMessage());
            }
        }
        return failures;
    }

    public static final class Check {

        private final String name;
        private final boolean passed;
        private final String message;

        public Check(String name, boolean passed, String message) {
            this.name = name;
            this.passed = passed;
            this.message = message;
        }

        public String getName() {
            return name;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getMessage() {
            return message;
        }
    }
}
