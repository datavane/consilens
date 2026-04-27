package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool that discovers table schemas from a JDBC database connection.
 */
@Slf4j
public class SchemaDiscoveryTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "consilens_schema_discover";
    }

    @Override
    public String getDescription() {
        return "Discovers the schema (columns, types, primary keys) of a table via JDBC connection URL";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("url").put("type", "string").put("description", "JDBC connection URL");
        props.putObject("username").put("type", "string").put("description", "Database username");
        props.putObject("password").put("type", "string").put("description", "Database password (never logged or exposed)");
        props.putObject("schema").put("type", "string").put("description", "Schema/database name (optional)");
        props.putObject("table").put("type", "string").put("description", "Table name");
        schema.putArray("required").add("url").add("username").add("table");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String url = input.path("url").asText();
        String username = input.path("username").asText();
        String password = input.path("password").asText("");
        String schemaName = input.path("schema").asText(null);
        String tableName = input.path("table").asText();

        if (url.isEmpty() || tableName.isEmpty()) {
            return ToolResult.failure("Required parameters missing: url and table are required");
        }

        try {
            log.debug("Discovering schema: url={}, table={}, schema={}", url, tableName, schemaName);
            
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                DatabaseMetaData meta = conn.getMetaData();
                StringBuilder result = new StringBuilder();
                result.append("## Schema for table: ").append(tableName).append("\n\n");
                result.append("### Columns\n");
                result.append("| Column | Type | Nullable | Size |\n");
                result.append("|--------|------|----------|------|\n");

                List<String> pkColumns = new ArrayList<>();
                try (ResultSet pkRs = meta.getPrimaryKeys(null, schemaName, tableName)) {
                    while (pkRs.next()) {
                        pkColumns.add(pkRs.getString("COLUMN_NAME"));
                    }
                }

                int columnCount = 0;
                try (ResultSet cols = meta.getColumns(null, schemaName, tableName, "%")) {
                    while (cols.next()) {
                        columnCount++;
                        String colName = cols.getString("COLUMN_NAME");
                        String typeName = cols.getString("TYPE_NAME");
                        int colSize = cols.getInt("COLUMN_SIZE");
                        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "YES" : "NO";
                        String pkMark = pkColumns.contains(colName) ? " (PK)" : "";
                        result.append(String.format("| %s%s | %s(%d) | %s | %d |\n",
                                colName, pkMark, typeName, colSize, nullable, colSize));
                    }
                }

                if (columnCount == 0) {
                    log.warn("No columns found for table: {}", tableName);
                    return ToolResult.failure("Table not found or has no accessible columns: " + tableName);
                }

                if (!pkColumns.isEmpty()) {
                    result.append("\n**Primary Key(s):** ").append(String.join(", ", pkColumns));
                } else {
                    result.append("\n**Note:** No primary key defined for this table.");
                }

                log.info("Schema discovery completed: table={}, columns={}, pks={}", 
                        tableName, columnCount, pkColumns.size());
                return ToolResult.success(result.toString());
            }
        } catch (Exception e) {
            log.error("Schema discovery failed for table {}: {}", tableName, e.getMessage(), e);
            return ToolResult.failure("Schema discovery failed: " + e.getMessage());
        }
    }
}
