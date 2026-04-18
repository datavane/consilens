package com.consilens.sink.table;

import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableResultSinkTest {

    @Test
    void shouldWriteErrorRowInCustomColumnMode() throws Exception {
        String url = "jdbc:h2:mem:table_result_sink;MODE=MySQL;DB_CLOSE_DELAY=-1";
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("result");
        sinkConfig.setProperties("{"
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
}
