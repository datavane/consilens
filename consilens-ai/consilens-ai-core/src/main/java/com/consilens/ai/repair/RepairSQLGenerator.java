package com.consilens.ai.repair;

import com.consilens.ai.model.RepairPlan;
import com.consilens.ai.model.RepairPlan.RepairStatement;
import com.consilens.ai.model.RepairPlan.TargetSide;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates SQL repair statements for a diff result.
 */
@Slf4j
public class RepairSQLGenerator {

    private final String tableName;

    public RepairSQLGenerator(String tableName) {
        this.tableName = tableName;
    }

    /**
     * Generates a full repair plan for the given diff result.
     *
     * @param diffResult the diff result to generate repairs for
     * @param targetSide which side to fix
     * @return the repair plan
     */
    public RepairPlan generate(DiffResult diffResult, TargetSide targetSide) {
        if (diffResult == null || diffResult.getDifferences() == null || diffResult.getDifferences().isEmpty()) {
            return RepairPlan.builder()
                    .targetSide(targetSide)
                    .summary("No differences found – no repair needed.")
                    .totalAffectedRows(0)
                    .build();
        }

        boolean fixTarget = targetSide == TargetSide.TARGET;
        List<RepairStatement> statements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (DiffRow row : diffResult.getDifferences()) {
            RepairStatement stmt = buildStatement(row, fixTarget);
            if (stmt != null) {
                statements.add(stmt);
            }
        }

        int totalAffected = statements.stream()
                .mapToInt(RepairStatement::getAffectedRows)
                .sum();

        String summary = String.format("Generated %d repair statement(s) to fix %s side. Total affected rows: %d",
                statements.size(), targetSide.name().toLowerCase(), totalAffected);

        return RepairPlan.builder()
                .targetSide(targetSide)
                .statements(statements)
                .summary(summary)
                .totalAffectedRows(totalAffected)
                .warnings(warnings)
                .build();
    }

    private RepairStatement buildStatement(DiffRow row, boolean fixTarget) {
        DiffOperation op = row.getOperation();

        if (op == DiffOperation.MISMATCH) {
            String sql = buildUpdate(row, fixTarget);
            return RepairStatement.builder()
                    .sql(sql)
                    .operation("UPDATE")
                    .description("Fix mismatched values")
                    .affectedRows(1)
                    .build();
        } else if (op == DiffOperation.TARGET_MISSING && fixTarget) {
            String sql = buildInsert(row, false);
            return RepairStatement.builder()
                    .sql(sql)
                    .operation("INSERT")
                    .description("Insert row missing in target")
                    .affectedRows(1)
                    .build();
        } else if (op == DiffOperation.SOURCE_MISSING && !fixTarget) {
            String sql = buildInsert(row, true);
            return RepairStatement.builder()
                    .sql(sql)
                    .operation("INSERT")
                    .description("Insert row missing in source")
                    .affectedRows(1)
                    .build();
        } else if (op == DiffOperation.SOURCE_MISSING && fixTarget) {
            String sql = buildDelete(row);
            return RepairStatement.builder()
                    .sql(sql)
                    .operation("DELETE")
                    .description("Delete extra row from target (not in source)")
                    .affectedRows(1)
                    .build();
        } else if (op == DiffOperation.TARGET_MISSING && !fixTarget) {
            String sql = buildDelete(row);
            return RepairStatement.builder()
                    .sql(sql)
                    .operation("DELETE")
                    .description("Delete extra row from source (not in target)")
                    .affectedRows(1)
                    .build();
        }
        return null;
    }

    private String buildInsert(DiffRow row, boolean useTarget) {
        List<String> columns = useTarget
                ? (row.getColumnNames2().isEmpty() ? row.getColumnNames1() : row.getColumnNames2())
                : (row.getColumnNames1().isEmpty() ? row.getColumnNames2() : row.getColumnNames1());
        List<Object> values = useTarget ? row.getAllTargetValues() : row.getAllSourceValues();

        if (columns.isEmpty()) {
            return "-- INSERT: no column metadata for PK " + row.getPrimaryKey();
        }
        String colList = String.join(", ", columns);
        String valList = values.stream().map(this::formatValue).collect(Collectors.joining(", "));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, colList, valList);
    }

    private String buildUpdate(DiffRow row, boolean useSourceValues) {
        List<String> columns = row.getColumnNames1().isEmpty() ? row.getColumnNames2() : row.getColumnNames1();
        List<Object> values = useSourceValues ? row.getAllSourceValues() : row.getAllTargetValues();

        if (columns.isEmpty()) {
            return "-- UPDATE: no column metadata for PK " + row.getPrimaryKey();
        }
        String setClauses = IntStream.range(0, Math.min(columns.size(), values.size()))
                .mapToObj(i -> columns.get(i) + " = " + formatValue(values.get(i)))
                .collect(Collectors.joining(", "));
        String whereClause = buildWhereClause(row);
        return String.format("UPDATE %s SET %s WHERE %s", tableName, setClauses, whereClause);
    }

    private String buildDelete(DiffRow row) {
        return String.format("DELETE FROM %s WHERE %s", tableName, buildWhereClause(row));
    }

    private String buildWhereClause(DiffRow row) {
        List<Object> pk = row.getPrimaryKey();
        if (pk == null || pk.isEmpty()) {
            return "1=1 /* WARNING: no primary key */";
        }
        if (pk.size() == 1) {
            return "id = " + formatValue(pk.get(0));
        }
        return IntStream.range(0, pk.size())
                .mapToObj(i -> "col" + i + " = " + formatValue(pk.get(i)))
                .collect(Collectors.joining(" AND "));
    }

    private String formatValue(Object val) {
        if (val == null) return "NULL";
        if (val instanceof Number) return val.toString();
        return "'" + val.toString().replace("'", "''") + "'";
    }
}
