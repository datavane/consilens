package com.consilens.ai.tool;

import com.consilens.core.algorithm.LocalDiffEngine;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Tool that performs a real diff between two database tables via JDBC.
 */
@Slf4j
public class DiffTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 10000;

    @Override
    public String getName() {
        return "consilens_diff";
    }

    @Override
    public String getDescription() {
        return "Compares two database tables and returns a diff result with all differences found";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("source_url").put("type", "string").put("description", "Source JDBC URL");
        props.putObject("source_username").put("type", "string").put("description", "Source DB username");
        props.putObject("source_password").put("type", "string").put("description", "Source DB password (never logged or exposed)");
        props.putObject("source_table").put("type", "string").put("description", "Source table (schema.table)");
        props.putObject("target_url").put("type", "string").put("description", "Target JDBC URL");
        props.putObject("target_username").put("type", "string").put("description", "Target DB username");
        props.putObject("target_password").put("type", "string").put("description", "Target DB password (never logged or exposed)");
        props.putObject("target_table").put("type", "string").put("description", "Target table (schema.table)");
        props.putObject("primary_keys").put("type", "string").put("description", "Comma-separated primary key columns");
        props.putObject("limit").put("type", "integer").put("description", "Max rows to fetch per table (default 10000)");
        schema.putArray("required").add("source_url").add("source_username").add("source_table")
                .add("target_url").add("target_username").add("target_table").add("primary_keys");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String sourceUrl = input.path("source_url").asText();
        String sourceUser = input.path("source_username").asText();
        String sourcePass = input.path("source_password").asText("");
        String sourceTable = input.path("source_table").asText();
        String targetUrl = input.path("target_url").asText();
        String targetUser = input.path("target_username").asText();
        String targetPass = input.path("target_password").asText("");
        String targetTable = input.path("target_table").asText();
        String primaryKeys = input.path("primary_keys").asText();
        int limit = input.path("limit").asInt(DEFAULT_LIMIT);

        if (sourceUrl.isEmpty() || targetUrl.isEmpty() || sourceTable.isEmpty() || primaryKeys.isEmpty()) {
            return ToolResult.failure("Required parameters: source_url, source_username, source_table, target_url, target_username, target_table, primary_keys");
        }

        List<String> pkColumns = new ArrayList<>();
        for (String pk : primaryKeys.split(",")) {
            pkColumns.add(pk.trim());
        }

        try {
            log.debug("Executing diff: source={} table={}, target={} table={}, pk_count={}, limit={}",
                    sourceUrl, sourceTable, targetUrl, targetTable, pkColumns.size(), limit);
            
            TableData sourceData = fetchTable(sourceUrl, sourceUser, sourcePass, sourceTable, limit);
            TableData targetData = fetchTable(targetUrl, targetUser, targetPass, targetTable, limit);

            List<DiffRow> differences = LocalDiffEngine.findDifferences(
                    sourceData.rows, targetData.rows,
                    pkColumns, sourceData.nonKeyColumns,
                    pkColumns, targetData.nonKeyColumns
            );

            DiffResult diffResult = DiffResult.builder()
                    .differences(differences)
                    .completedAt(Instant.now())
                    .metadata(Collections.singletonMap("sourceTable", sourceTable))
                    .build();

            String resultId = "diff_" + UUID.randomUUID().toString().substring(0, 8);
            context.getConversation().storeDiffResult(resultId, diffResult);

            StringBuilder summary = new StringBuilder();
            summary.append("## Diff completed\n\n");
            summary.append("- **Result ID:** ").append(resultId).append("\n");
            summary.append("- **Source table:** ").append(sourceTable).append(" (").append(sourceData.rows.size()).append(" rows)\n");
            summary.append("- **Target table:** ").append(targetTable).append(" (").append(targetData.rows.size()).append(" rows)\n");
            summary.append("- **Total differences:** ").append(differences.size()).append("\n");

            long mismatches = differences.stream().filter(r -> r.getOperation().name().equals("MISMATCH")).count();
            long targetMissing = differences.stream().filter(r -> r.getOperation().name().equals("TARGET_MISSING")).count();
            long sourceMissing = differences.stream().filter(r -> r.getOperation().name().equals("SOURCE_MISSING")).count();
            summary.append("  - Mismatches: ").append(mismatches).append("\n");
            summary.append("  - Missing in target: ").append(targetMissing).append("\n");
            summary.append("  - Missing in source: ").append(sourceMissing).append("\n");

            log.info("Diff completed: resultId={}, differences={}", resultId, differences.size());
            return ToolResult.success(summary.toString(), diffResult);
        } catch (Exception e) {
            log.error("Diff failed: {}", e.getMessage(), e);
            return ToolResult.failure("Diff failed: " + e.getMessage());
        }
    }

    private TableData fetchTable(String url, String username, String password, String table, int limit) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        List<String> allColumns = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(limit);
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    allColumns.add(meta.getColumnName(i));
                }
                while (rs.next()) {
                    Object[] row = new Object[colCount];
                    for (int i = 0; i < colCount; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    rows.add(row);
                }
            }
        }
        return new TableData(rows, allColumns);
    }

    private static class TableData {
        final List<Object[]> rows;
        final List<String> nonKeyColumns;

        TableData(List<Object[]> rows, List<String> allColumns) {
            this.rows = rows;
            this.nonKeyColumns = allColumns;
        }
    }
}
