package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliConfigurationTest {

    @Test
    void shouldValidateFullConfigurationWithTableSink() {
        CliConfiguration config = baseConfig();

        assertDoesNotThrow(config::validate);
        assertDoesNotThrow(config::validateDatabaseConnections);
    }

    @Test
    void shouldRejectUnsupportedStrategyConfiguration() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("local")
                .algorithm("concat")
                .bisectionFactor(4)
                .batchSize(1000)
                .maxDifferences(1L)
                .build());

        assertThrows(ValidationException.class, config::validate);
    }

    @Test
    void shouldRejectTableSinkWithCollidingSanitizedColumns() {
        CliConfiguration config = baseConfig();
        SinkConfig tableSink = config.getResult().getSinks().get(0);
        tableSink.setProperties("{\"type\":\"mysql\",\"url\":\"jdbc:mysql://localhost:3306/test\",\"columns\":["
                + "{\"name\":\"a-b\",\"value\":\"${operation}\"},"
                + "{\"name\":\"a_b\",\"value\":\"${operation}\"}]}");

        assertThrows(ValidationException.class, config::validate);
    }

    @Test
    void shouldRejectTableSinkWithUnsupportedDatabaseType() {
        CliConfiguration config = baseConfig();
        SinkConfig tableSink = config.getResult().getSinks().get(0);
        tableSink.setProperties("{\"type\":\"oracle\",\"url\":\"jdbc:oracle:thin:@localhost:1521/xe\"}");

        assertThrows(ValidationException.class, config::validate);
    }

    private CliConfiguration baseConfig() {
        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/source")
                                .username("root")
                                .password("secret")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("source_table").build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("postgresql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:postgresql://localhost:5432/target")
                                .username("root")
                                .password("secret")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("target_table").build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("concat")
                        .bisectionFactor(4)
                        .batchSize(1000)
                        .maxDifferences(1_000L)
                        .build())
                .result(ResultConfig.builder()
                        .sinks(List.of(tableSink()))
                        .build())
                .build();
    }

    private SinkConfig tableSink() {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{\"type\":\"mysql\",\"url\":\"jdbc:mysql://localhost:3306/test\"}");
        return sinkConfig;
    }
}
