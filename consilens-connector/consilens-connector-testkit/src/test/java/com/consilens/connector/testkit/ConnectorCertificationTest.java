package com.consilens.connector.testkit;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.file.FileConnectorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorCertificationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCertifyFileConnector() throws Exception {
        Path csv = tempDir.resolve("users.csv");
        Files.writeString(csv, "id,name\n1,alice\n2,bob\n");
        ConnectorConfig config = config(csv.toString(), "file");

        CertificationReport report = ConnectorCertification.verify(
                new FileConnectorProvider(), config, KeySpec.builder().fields(List.of("id")).build(), 10);

        assertThat(report.failures()).isEmpty();
        assertThat(report.isPassed()).isTrue();
        assertThat(report.getRecordsRead()).isEqualTo(2);
        assertThat(report.getChecks()).extracting(CertificationReport.Check::getName)
                .contains("provider-type", "adapter.create", "dataset.open", "schema",
                        "metadata", "record-scanner", "scan", "scan.deterministic");
    }

    @Test
    void shouldFailWhenDatasetCannotOpen() {
        ConnectorConfig config = config(tempDir.resolve("missing.csv").toString(), "file");

        CertificationReport report = ConnectorCertification.verify(
                new FileConnectorProvider(), config);

        assertThat(report.isPassed()).isFalse();
        assertThat(report.failures()).anySatisfy(failure ->
                assertThat(failure).contains("dataset.open"));
    }

    @Test
    void shouldFailOnProviderTypeMismatch() {
        ConnectorConfig config = config(tempDir.resolve("users.csv").toString(), "mysql");

        CertificationReport report = ConnectorCertification.verify(
                new FileConnectorProvider(), config);

        assertThat(report.isPassed()).isFalse();
        assertThat(report.failures()).anySatisfy(failure ->
                assertThat(failure).contains("provider-type"));
    }

    private ConnectorConfig config(String path, String type) {
        return ConnectorConfig.builder()
                .type(type)
                .name("certification")
                .resource(ResourceLocator.builder()
                        .type("file")
                        .name(path)
                        .path(path)
                        .options(java.util.Map.of("format", "csv"))
                        .build())
                .build();
    }
}
