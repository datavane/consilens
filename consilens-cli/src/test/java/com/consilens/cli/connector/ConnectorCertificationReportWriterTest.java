package com.consilens.cli.connector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorCertificationReportWriterTest {

    @Test
    void shouldRejectUnknownFormat() {
        assertThatThrownBy(() -> ConnectorCertificationReportWriter.write(List.of(), "bogus", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported: json, markdown");
    }
}
