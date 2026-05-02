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

class TableDiffRecordSinkTest {

    @Test
    void shouldRejectNonPositiveBatchSize() {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType("diff-record");
        sinkConfig.setProperties("{"
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
}
