package com.consilens.cli.connector;

import com.consilens.connector.testkit.CertificationReport;

/**
 * Certification result for one side of a comparison config.
 */
public class SideCertification {

    private final String side;
    private final CertificationReport report;

    public SideCertification(String side, CertificationReport report) {
        this.side = side;
        this.report = report;
    }

    public String getSide() {
        return side;
    }

    public CertificationReport getReport() {
        return report;
    }
}
