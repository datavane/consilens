package com.consilens.cli.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricReportWriterTest {

    @Test
    void shouldRejectUnknownFormat() {
        assertThatThrownBy(() -> MetricReportWriter.write(List.of(), "bogus", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported: json, markdown");
    }

    @Test
    void shouldAcceptJsonAndMarkdown() {
        assertThatCode(() -> MetricReportWriter.write(List.of(), "json", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> MetricReportWriter.write(List.of(), "markdown", null))
                .doesNotThrowAnyException();
    }
}
