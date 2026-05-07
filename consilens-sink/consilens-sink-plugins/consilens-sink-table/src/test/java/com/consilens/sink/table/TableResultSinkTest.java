package com.consilens.sink.table;

import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.diff.DiffResult;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableResultSinkTest {

    @Test
    void shouldRejectCustomColumnsThatCollideAfterSanitization() {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"jdbc:h2:mem:result_sanitized_collision;MODE=MySQL;DB_CLOSE_DELAY=-1\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"result_collision\","
                + "\"columns\":["
                + "{\"name\":\"a-b\",\"value\":\"1\"},"
                + "{\"name\":\"a_b\",\"value\":\"2\"}"
                + "]"
                + "}");

        TableResultSink sink = new TableResultSink();
        DiffContext context = DiffContext.builder().taskId("task-collision").build();

        assertThrows(IllegalArgumentException.class, () -> sink.open(sinkConfig, context));
    }

    @Test
    void shouldWriteErrorRowInCustomColumnMode() throws Exception {
        String url = "jdbc:h2:mem:table_result_sink;MODE=MySQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"result_sink_test\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"columns\":["
                + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                + "{\"name\":\"run_status\",\"value\":\"${status}\",\"columnType\":\"VARCHAR(32)\"},"
                + "{\"name\":\"error_message\",\"columnType\":\"VARCHAR(255)\"}"
                + "]"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-123")
                .build();

        TableResultSink sink = new TableResultSink();
        sink.open(sinkConfig, context);
        sink.onError(context, new RuntimeException("boom"));
        sink.close();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT task_id, run_status, error_message FROM result_sink_test")) {
            assertTrue(resultSet.next());
            assertEquals("task-123", resultSet.getString(1));
            assertEquals("ERROR", resultSet.getString(2));
            assertEquals("boom", resultSet.getString(3));
        }
    }

    @Test
    void shouldBindConfiguredPostgresTypesInCustomColumnMode() throws Exception {
        String url = "jdbc:h2:mem:table_result_pg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{"
                + "\"type\":\"postgresql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"result_sink_pg_test\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"columns\":["
                + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                + "{\"name\":\"total_diff\",\"value\":\"3\",\"columnType\":\"INT8\"},"
                + "{\"name\":\"run_status\",\"value\":\"${status}\",\"columnType\":\"VARCHAR(32)\"}"
                + "]"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-result-pg")
                .build();
        DiffResult result = DiffResult.builder()
                .differences(java.util.Collections.singletonList(
                        com.consilens.core.diff.DiffRow.removed(List.of(1L), List.of("Alice"), List.of("name"))
                ))
                .build();

        TableResultSink sink = new TableResultSink();
        sink.open(sinkConfig, context);
        sink.onResult(result, context);
        sink.close();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT task_id, total_diff, run_status FROM result_sink_pg_test")) {
            assertTrue(resultSet.next());
            assertEquals("task-result-pg", resultSet.getString("task_id"));
            assertEquals(3L, resultSet.getLong("total_diff"));
            assertEquals("DIFF", resultSet.getString("run_status"));
        }
    }

    @Test
    void shouldNormalizeMysqlStyleDeclaredTypesForPostgresTarget() throws Exception {
        String url = "jdbc:h2:mem:table_result_pg_mysql_style;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{"
                + "\"type\":\"postgresql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"result_sink_pg_mysql_style\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"columns\":["
                + "{\"name\":\"job_execution_id\",\"value\":\"12121212\",\"columnType\":\"bigint(20)\"},"
                + "{\"name\":\"state\",\"value\":\"1\",\"columnType\":\"int(11)\"},"
                + "{\"name\":\"create_time\",\"value\":\"2025-05-26 22:35:35\",\"columnType\":\"datetime\"}"
                + "]"
                + "}");

        DiffContext context = DiffContext.builder()
                .taskId("task-pg-mysql-style")
                .build();
        DiffResult result = DiffResult.builder()
                .differences(java.util.Collections.singletonList(
                        com.consilens.core.diff.DiffRow.removed(List.of(1L), List.of("Alice"), List.of("name"))
                ))
                .build();

        TableResultSink sink = new TableResultSink();
        sink.open(sinkConfig, context);
        sink.onResult(result, context);
        sink.close();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT job_execution_id, state, create_time FROM result_sink_pg_mysql_style")) {
            assertTrue(resultSet.next());
            assertEquals(12121212L, resultSet.getLong("job_execution_id"));
            assertEquals(1, resultSet.getInt("state"));
            assertEquals("2025-05-26 22:35:35.0", resultSet.getTimestamp("create_time").toString());
        }
    }
}
