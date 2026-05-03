package com.consilens.sink.table;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDiffRecordSinkTest {

    @Test
    void shouldRejectNonPositiveBatchSize() {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("diff-record");
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"jdbc:h2:mem:invalid_batch_size;MODE=MySQL;DB_CLOSE_DELAY=-1\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"diff_record_invalid_batch\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"batchSize\":0"
                + "}");

        TableDiffRecordSink sink = new TableDiffRecordSink();
        DiffContext context = DiffContext.builder().taskId("task-invalid-batch").build();

        assertThrows(IllegalArgumentException.class, () -> sink.open(sinkConfig, context));
    }

    @Test
    void shouldRollbackEntireTransactionWhenBatchInsertFails() throws Exception {
        String url = "jdbc:h2:mem:diff_record_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("diff-record");
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"diff_record_rollback_test\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"batchSize\":1,"
                + "\"columns\":["
                + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64) PRIMARY KEY\"},"
                + "{\"name\":\"diff_type\",\"value\":\"${operation}\",\"columnType\":\"VARCHAR(20) NOT NULL\"}"
                + "]"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-rollback")
                .build();

        TableDiffRecordSink sink = new TableDiffRecordSink();
        sink.open(sinkConfig, context);
        try {
            List<DiffRow> rows = List.of(
                    DiffRow.removed(List.of(1), List.of("Alice"), List.of("name")),
                    DiffRow.modified(List.of(2), List.of("Bob"), List.of("Bobby"), List.of("name"), List.of("name"))
            );

            assertThrows(SQLException.class, () -> sink.onDiffRecords(rows, context));
        } finally {
            sink.close();
        }

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM diff_record_rollback_test")) {
            resultSet.next();
            assertEquals(0, resultSet.getInt(1));
        }
    }

    @Test
    void shouldBindConfiguredPostgresTypesInCustomColumnMode() throws Exception {
        String url = "jdbc:h2:mem:diff_record_pg_custom;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("diff-record");
        sinkConfig.setProperties("{"
                + "\"type\":\"postgresql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"diff_record_pg_custom_test\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"batchSize\":100,"
                + "\"columns\":["
                + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                + "{\"name\":\"pk_value\",\"value\":\"${primaryKey}\",\"columnType\":\"INT8\"},"
                + "{\"name\":\"device_id\",\"value\":\"${src.device_id}\",\"columnType\":\"INT8\"},"
                + "{\"name\":\"monitor_name\",\"value\":\"${tgt.monitor_name}\",\"columnType\":\"VARCHAR(255)\"}"
                + "]"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-pg-types")
                .build();

        DiffRow row = DiffRow.modified(
                List.of(1L),
                List.of(42L, "旧状态"),
                List.of(42L, "状态"),
                List.of("device_id", "monitor_name"),
                List.of("device_id", "monitor_name")
        );

        TableDiffRecordSink sink = new TableDiffRecordSink();
        sink.open(sinkConfig, context);
        try {
            sink.onDiffRecords(List.of(row), context);
        } finally {
            sink.close();
        }

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT task_id, pk_value, device_id, monitor_name FROM diff_record_pg_custom_test")) {
            assertTrue(resultSet.next());
            assertEquals("task-pg-types", resultSet.getString("task_id"));
            assertEquals(1L, resultSet.getLong("pk_value"));
            assertEquals(42L, resultSet.getLong("device_id"));
            assertEquals("状态", resultSet.getString("monitor_name"));
        }
    }

    @Test
    void shouldWriteDefaultJsonColumnsInPostgresMode() throws Exception {
        String url = "jdbc:h2:mem:diff_record_pg_default;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("diff-record");
        sinkConfig.setProperties("{"
                + "\"type\":\"postgresql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"diff_record_pg_default_test\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"batchSize\":100"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-pg-default")
                .sourceColumnNames(List.of("device_id"))
                .targetColumnNames(List.of("device_id"))
                .build();

        DiffRow row = DiffRow.modified(
                List.of(1L),
                List.of(42L),
                List.of(43L),
                List.of("device_id"),
                List.of("device_id"),
                List.of("device_id"),
                List.of("device_id")
        );

        TableDiffRecordSink sink = new TableDiffRecordSink();
        sink.open(sinkConfig, context);
        try {
            sink.onDiffRecords(List.of(row), context);
        } finally {
            sink.close();
        }

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT nl_dq_diff_columns1, device_id_1, device_id_2 FROM diff_record_pg_default_test")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString("nl_dq_diff_columns1").contains("device_id"));
            assertEquals("42", resultSet.getString("device_id_1"));
            assertEquals("43", resultSet.getString("device_id_2"));
        }
    }
}
