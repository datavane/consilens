package com.consilens.performance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerformanceTestConfigTest {

    @Test
    void shouldValidateMinimalRunnableConfig() {
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .testName("smoke")
                .build();

        assertDoesNotThrow(config::validate);
        assertEquals(5, config.getWarmupIterations());
        assertEquals(10, config.getTestIterations());
        assertEquals(1, config.getConcurrencyLevel());
        assertEquals(PerformanceTestConfig.LoadPattern.CONSTANT, config.getLoadPattern());
    }

    @Test
    void shouldRejectInvalidExecutionSettings() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName(" ")
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .loadPattern(null)
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .testIterations(0)
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .concurrencyLevel(0)
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .monitoringIntervalMs(0)
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .testDuration(Duration.ZERO)
                .build()
                .validate());
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("bad")
                .testParameters(null)
                .build()
                .validate());
    }

    @Test
    void shouldValidateDatabaseAndTableConfigWhenPresent() {
        PerformanceTestConfig valid = PerformanceTestConfig.builder()
                .testName("database")
                .databaseConfig(PerformanceTestConfig.DatabaseConfig.builder()
                        .jdbcUrl("jdbc:h2:mem:perf")
                        .username("sa")
                        .sourceTable(PerformanceTestConfig.TableConfig.builder()
                                .tableName("source_orders")
                                .keyColumns(new String[]{"id"})
                                .rowLimit(100)
                                .build())
                        .targetTable(PerformanceTestConfig.TableConfig.builder()
                                .tableName("target_orders")
                                .keyColumns(new String[]{"id"})
                                .build())
                        .build())
                .testParameters(new HashMap<>())
                .build();

        assertDoesNotThrow(valid::validate);

        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("database")
                .databaseConfig(PerformanceTestConfig.DatabaseConfig.builder()
                        .jdbcUrl("jdbc:h2:mem:perf")
                        .username("sa")
                        .sourceTable(PerformanceTestConfig.TableConfig.builder()
                                .tableName("orders")
                                .keyColumns(new String[]{" "})
                                .build())
                        .build())
                .build()
                .validate());

        assertThrows(IllegalArgumentException.class, () -> PerformanceTestConfig.builder()
                .testName("database")
                .databaseConfig(PerformanceTestConfig.DatabaseConfig.builder()
                        .jdbcUrl("jdbc:h2:mem:perf")
                        .username("sa")
                        .sourceTable(PerformanceTestConfig.TableConfig.builder()
                                .tableName("orders")
                                .keyColumns(new String[]{"id"})
                                .rowLimit(-1)
                                .build())
                        .build())
                .build()
                .validate());
    }
}
