package com.consilens.cli.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractReportWriterTest {

    @Test
    void shouldRejectUnknownFormat() {
        assertThatThrownBy(() -> ContractReportWriter.write(null, "bogus", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported: json, markdown");
    }
}
