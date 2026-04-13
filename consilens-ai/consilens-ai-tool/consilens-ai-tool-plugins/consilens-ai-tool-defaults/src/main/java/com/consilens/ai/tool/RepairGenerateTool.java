package com.consilens.ai.tool;

import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tool that generates repair SQL statements for a diff result.
 */
@Slf4j
public class RepairGenerateTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "consilens_repair_generate";
    }

    @Override
    public String getDescription() {
        return "Generates SQL repair statements to fix data inconsistencies found in a diff result";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("result_id").put("type", "string").put("description", "ID of a previously stored diff result");
        props.putObject("target_side").put("type", "string")
                .put("enum", "source,target")
                .put("description", "Which side to fix: 'source' or 'target' (default: target)");
        props.putObject("table_name").put("type", "string").put("description", "Target table name for SQL generation");
        props.putObject("limit").put("type", "integer").put("description", "Maximum number of SQL statements to generate (default: 100)");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String targetSide = input.path("target_side").asText("target");
        String tableName = input.path("table_name").asText(null);
        int limit = input.path("limit").asInt(100);

        DiffResult diffResult = loadDiffResult(input, context);
        if (diffResult == null) {
            return ToolResult.failure("No diff result found. Run a diff first or provide result_id.");
        }

        if (diffResult.getDifferences() == null || diffResult.getDifferences().isEmpty()) {
            return ToolResult.success("No differences found – no repair SQL needed.");
        }

        boolean fixTarget = !"source".equalsIgnoreCase(targetSide);
        String tableRef = tableName != null ? tableName : "your_table";

        List<String> statements = generateRepairSql(diffResult, fixTarget, tableRef, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("## Repair SQL (fixing ").append(fixTarget ? "target" : "source").append(" side)\n\n");
        sb.append("Generated ").append(statements.size()).append(" statement(s):\n\n```sql\n");
        statements.forEach(s -> sb.append(s).append(";\n"));
        sb.append("```\n");

        if (diffResult.getDifferences().size() > limit) {
            sb.append("\n⚠️ Showing first ").append(limit).append(" of ")
                    .append(diffResult.getDifferences().size()).append(" differences.");
        }

        return ToolResult.success(sb.toString(), statements);
    }

    private DiffResult loadDiffResult(JsonNode input, ToolContext context) {
        String resultId = input.path("result_id").asText(null);
        if (resultId != null && !resultId.isEmpty()) {
            Optional<DiffResult> stored = context.getConversation().getDiffResult(resultId);
            if (stored.isPresent()) return stored.get();
        }
        return context.getConversation().getLatestDiffResult().orElse(null);
    }

    private List<String> generateRepairSql(DiffResult diffResult, boolean fixTarget, String tableName, int limit) {
        return diffResult.getDifferences().stream()
                .limit(limit)
                .map(row -> buildStatement(row, fixTarget, tableName))
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    private String buildStatement(DiffRow row, boolean fixTarget, String tableName) {
        List<Object> pkValues = row.getPrimaryKey();
        if (row.getOperation() == DiffOperation.MISMATCH) {
            // UPDATE the side we're fixing
            if (fixTarget) {
                return buildUpdate(tableName, row);
            } else {
                return buildUpdate(tableName, row);
            }
        } else if (row.getOperation() == DiffOperation.TARGET_MISSING && fixTarget) {
            // Row missing in target, INSERT it
            return buildInsert(tableName, row);
        } else if (row.getOperation() == DiffOperation.SOURCE_MISSING && !fixTarget) {
            // Row missing in source, INSERT it
            return buildInsert(tableName, row);
        } else if (row.getOperation() == DiffOperation.TARGET_MISSING && !fixTarget) {
            // Row extra in source (missing in target), DELETE from source
            return buildDelete(tableName, row);
        } else if (row.getOperation() == DiffOperation.SOURCE_MISSING && fixTarget) {
            // Row extra in target (missing in source), DELETE from target
            return buildDelete(tableName, row);
        }
        return null;
    }

    private String buildInsert(String tableName, DiffRow row) {
        List<String> columns = row.getColumnNames1().isEmpty() ? row.getColumnNames2() : row.getColumnNames1();
        List<Object> values = row.getAllSourceValues().isEmpty() ? row.getAllTargetValues() : row.getAllSourceValues();
        if (columns.isEmpty()) {
            return "-- INSERT: no column metadata available for row with PK " + row.getPrimaryKey();
        }
        String colList = columns.stream().collect(Collectors.joining(", "));
        String valList = values.stream().map(this::formatValue).collect(Collectors.joining(", "));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, colList, valList);
    }

    private String buildUpdate(String tableName, DiffRow row) {
        List<String> columns = row.getColumnNames1();
        if (columns.isEmpty()) {
            return "-- UPDATE: no column metadata for row with PK " + row.getPrimaryKey();
        }
        List<Object> values = row.getAllSourceValues();
        String setClauses = IntStream.range(0, Math.min(columns.size(), values.size()))
                .mapToObj(i -> columns.get(i) + " = " + formatValue(values.get(i)))
                .collect(Collectors.joining(", "));
        String whereClause = row.getPrimaryKey().isEmpty() ? "1=1 /* no PK */" :
                "id = " + formatValue(row.getPrimaryKey().get(0));
        return String.format("UPDATE %s SET %s WHERE %s", tableName, setClauses, whereClause);
    }

    private String buildDelete(String tableName, DiffRow row) {
        String whereClause = row.getPrimaryKey().isEmpty() ? "1=1 /* no PK */" :
                "id = " + formatValue(row.getPrimaryKey().get(0));
        return String.format("DELETE FROM %s WHERE %s", tableName, whereClause);
    }

    private String formatValue(Object val) {
        if (val == null) return "NULL";
        if (val instanceof Number) return val.toString();
        return "'" + val.toString().replace("'", "''") + "'";
    }
}
