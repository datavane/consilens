package com.consilens.sink.csv;

import com.consilens.common.io.PathSupport;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outputs diff records to a CSV file with streaming writes (flush per batch).
 *
 * <p>Three output modes depending on {@code columns} and {@code mergeDefaults}:
 * <ul>
 *   <li><b>Default mode</b> ({@code columns} empty): four fixed columns — operation / primaryKey / sourceValues / targetValues.</li>
 *   <li><b>Full custom mode</b> ({@code columns} non-empty, {@code mergeDefaults=false}): only the configured columns.</li>
 *   <li><b>Merge mode</b> ({@code columns} non-empty, {@code mergeDefaults=true}): default columns with value overrides,
 *       plus any extra columns appended after defaults.</li>
 * </ul>
 */
@Slf4j
public class CsvDiffRecordSink implements Sink {

    private static final List<String> DEFAULT_COLUMNS = Arrays.asList("operation", "primaryKey", "sourceValues", "targetValues");

    private BufferedWriter writer;
    /** 容差内记录写旁挂文件：不改变主文件的列契约，也不混进"差异行数"。 */
    private BufferedWriter withinToleranceWriter;
    private CsvSinkConfig sinkConfig;
    private Path mainPath;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        sinkConfig = parseConfig(config.getProperties());
        String path = sinkConfig.getPath();
        String resolvedPath = ColumnValueInterpolator.resolvePath(
                path != null ? path : "diff-record-${taskId}.csv", context);
        Path filePath = Paths.get(resolvedPath);
        mainPath = filePath;
        PathSupport.ensureParent(filePath);
        writer = Files.newBufferedWriter(filePath);
        if (sinkConfig.isIncludeHeader()) {
            writeHeader(writer);
        }
        // 同路径重跑时清掉上一轮的旁挂文件：否则本轮没有容差内行时，上一轮的旧文件会被误当成这次的结论
        Path stale = withinTolerancePath();
        try {
            if (Files.deleteIfExists(stale)) {
                log.debug("Removed stale within-tolerance report: {}", stale);
            }
        } catch (IOException e) {
            // 清不掉旧文件最多让报告多一份过期样本，不该让整次 compare 失败（failOnSinkError 默认 true）
            log.warn("Failed to remove stale within-tolerance report {}: {}", stale, e.getMessage());
        }
    }

    private List<String> headers() {
        if (sinkConfig.isMergeMode()) {
            return buildMergeHeaders();
        }
        if (sinkConfig.hasCustomColumns()) {
            List<String> custom = new ArrayList<>();
            for (ColumnMapping col : sinkConfig.getColumns()) {
                custom.add(col.getName());
            }
            return custom;
        }
        return DEFAULT_COLUMNS;
    }

    private void writeHeader(BufferedWriter target) throws IOException {
        // 表头同样走 CSV 转义：自定义列名可能含分隔符/引号
        target.write(String.join(sinkConfig.getDelimiter(),
                headers().stream().map(this::escapeCSV).collect(java.util.stream.Collectors.toList())));
        target.newLine();
        target.flush();
    }

    /**
     * 容差内（未计入差异）的行写到 {@code <name>.within-tolerance.csv}：既能在报告里单独看到，
     * 又不会污染主文件的差异行数和列契约。文件在第一次收到这类行时才创建。
     */
    @Override
    public void onWithinToleranceRecords(List<DiffRow> rows, DiffContext context) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (withinToleranceWriter == null) {
            withinToleranceWriter = Files.newBufferedWriter(withinTolerancePath());
            if (sinkConfig.isIncludeHeader()) {
                writeHeader(withinToleranceWriter);
            }
        }
        for (DiffRow row : rows) {
            withinToleranceWriter.write(buildLine(row, context));
            withinToleranceWriter.newLine();
        }
        withinToleranceWriter.flush();
    }

    /** 主文件同目录、同名加 {@code .within-tolerance} 后缀；无扩展名时直接追加后缀。 */
    Path withinTolerancePath() {
        String fileName = mainPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String suffix = ".within-tolerance.csv";
        String sibling = dot > 0
                ? fileName.substring(0, dot) + suffix
                : fileName + suffix;
        return mainPath.getParent() == null ? Paths.get(sibling) : mainPath.getParent().resolve(sibling);
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) throws IOException {
        for (DiffRow row : rows) {
            writer.write(buildLine(row, context));
            writer.newLine();
        }
        writer.flush();
    }

    private String buildLine(DiffRow row, DiffContext context) {
        String delim = sinkConfig.getDelimiter();
        if (sinkConfig.isMergeMode()) {
            return buildMergeLine(row, context, delim);
        } else if (sinkConfig.hasCustomColumns()) {
            StringBuilder sb = new StringBuilder();
            List<ColumnMapping> cols = sinkConfig.getColumns();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(delim);
                sb.append(escapeCSV(ColumnValueInterpolator.resolveField(cols.get(i), context, row)));
            }
            return sb.toString();
        } else {
            return escapeCSV(row.getOperation().name()) + delim
                    + escapeCSV(row.getPrimaryKeyString()) + delim
                    + escapeCSV(row.getAllSourceValues().toString()) + delim
                    + escapeCSV(row.getAllTargetValues().toString());
        }
    }

    private String buildMergeLine(DiffRow row, DiffContext context, String delim) {
        Map<String, ColumnMapping> overrideMap = buildOverrideMap();
        List<String> headers = buildMergeHeaders();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(delim);
            String colName = headers.get(i);
            String value;
            if (overrideMap.containsKey(colName)) {
                value = ColumnValueInterpolator.resolveField(overrideMap.get(colName), context, row);
                if (value == null) value = defaultValue(colName, row);
            } else {
                value = defaultValue(colName, row);
            }
            sb.append(escapeCSV(value));
        }
        return sb.toString();
    }

    /** Default column headers for merge mode: defaults + extra custom columns. */
    private List<String> buildMergeHeaders() {
        List<String> headers = new ArrayList<>(DEFAULT_COLUMNS);
        Map<String, ColumnMapping> overrideMap = buildOverrideMap();
        for (ColumnMapping cm : sinkConfig.getColumns()) {
            if (!DEFAULT_COLUMNS.contains(cm.getName())) {
                headers.add(cm.getName());
            }
        }
        return headers;
    }

    /** Build a name→mapping lookup from the configured columns list. */
    private Map<String, ColumnMapping> buildOverrideMap() {
        Map<String, ColumnMapping> map = new LinkedHashMap<>();
        if (sinkConfig.getColumns() != null) {
            for (ColumnMapping cm : sinkConfig.getColumns()) {
                map.put(cm.getName(), cm);
            }
        }
        return map;
    }

    /** Default value for a known default column. Returns empty string for unknown extras. */
    private String defaultValue(String colName, DiffRow row) {
        switch (colName) {
            case "operation":    return row.getOperation().name();
            case "primaryKey":   return row.getPrimaryKeyString();
            case "sourceValues": return row.getAllSourceValues().toString();
            case "targetValues": return row.getAllTargetValues().toString();
            default:             return "";
        }
    }


    /**
     * 防止 CSV 注入：以 = + @ 或（非数字的）- 开头的单元格会被 Excel/Sheets 当作公式执行。
     * 这类值统一加一个前导单引号，让表格软件按文本显示；纯数字（含负数）保持原样，
     * 否则会把 -12.5 这类合法数值改坏。
     */
    private static String defuseFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        boolean risky;
        if (first == '=' || first == '+' || first == '@') {
            risky = true;
        } else if (first == '-') {
            risky = !NUMERIC.matcher(value).matches();
        } else {
            risky = first == '\t' || first == '\r';
        }
        return risky ? "'" + value : value;
    }

    private static final java.util.regex.Pattern NUMERIC =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private String escapeCSV(String value) {
        if (value == null) return "";
        value = defuseFormula(value);
        String delim = sinkConfig.getDelimiter();
        if (value.contains(delim) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        // 两个 writer 必须都尝试关闭：主文件 close 抛异常时也要释放旁挂文件句柄
        IOException failure = null;
        try {
            if (writer != null) {
                writer.close();
                log.info("CsvDiffRecordSink closed: {}", sinkConfig != null ? sinkConfig.getPath() : "");
            }
        } catch (IOException e) {
            failure = e;
        }
        try {
            if (withinToleranceWriter != null) {
                withinToleranceWriter.close();
                log.info("CsvDiffRecordSink within-tolerance report closed: {}", withinTolerancePath());
            }
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private CsvSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new CsvSinkConfig();
        }
        return new ObjectMapper().readValue(properties, CsvSinkConfig.class);
    }
}
