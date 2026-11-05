package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.normalization.MatchSpec;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves per-column numeric tolerance from a {@link NormalizationSpec}.
 *
 * <p>Resolution order for a compared column:
 * <ol>
 *   <li>rule with {@code match.path} equal to the column name (column-level)</li>
 *   <li>rule with {@code match.type} matching the column's type family (type-level)</li>
 * </ol>
 * Only rules with operation {@code format_number} and a {@code tolerance}
 * (absolute) or {@code relativeTolerance} param produce a tolerance. Integer
 * columns never resolve a tolerance.
 */
public final class ColumnToleranceResolver {

    private ColumnToleranceResolver() {
    }

    /**
     * Builds a lowercase-column-name → tolerance map for the given compared columns.
     * Both sides' names are registered so positional comparison can resolve either.
     */
    public static Map<String, ColumnTolerance> resolve(NormalizationSpec spec,
                                                       List<String> columns1,
                                                       List<String> columns2,
                                                       Map<String, DataType> columnTypes1,
                                                       Map<String, DataType> columnTypes2) {
        Map<String, ColumnTolerance> tolerances = new LinkedHashMap<>();
        if (spec == null) {
            return tolerances;
        }
        int size = Math.max(columns1.size(), columns2.size());
        for (int index = 0; index < size; index++) {
            String sourceName = index < columns1.size() ? columns1.get(index) : null;
            String targetName = index < columns2.size() ? columns2.get(index) : null;
            DataType sourceType = columnType(columnTypes1, sourceName);
            DataType targetType = columnType(columnTypes2, targetName);
            ColumnTolerance tolerance = resolveForColumn(spec, sourceName, sourceType);
            if (tolerance == null && targetName != null) {
                tolerance = resolveForColumn(spec, targetName, targetType);
            }
            if (tolerance != null) {
                if (sourceName != null) {
                    tolerances.putIfAbsent(sourceName.toLowerCase(Locale.ROOT), tolerance);
                }
                if (targetName != null) {
                    tolerances.putIfAbsent(targetName.toLowerCase(Locale.ROOT), tolerance);
                }
            }
        }
        return tolerances;
    }

    private static ColumnTolerance resolveForColumn(NormalizationSpec spec, String column, DataType type) {
        if (column == null) {
            return null;
        }
        String family = typeFamily(type);
        ColumnTolerance byPath = findTolerance(spec, column, null);
        if (byPath != null) {
            return byPath;
        }
        if (family != null) {
            return findTolerance(spec, null, family);
        }
        return null;
    }

    private static ColumnTolerance findTolerance(NormalizationSpec spec, String path, String type) {
        List<List<NormalizationRule>> ruleGroups = new ArrayList<>();
        ruleGroups.add(spec.getGlobal());
        ruleGroups.add(spec.getSource());
        ruleGroups.add(spec.getTarget());
        for (List<NormalizationRule> rules : ruleGroups) {
            if (rules == null) {
                continue;
            }
            for (NormalizationRule rule : rules) {
                if (rule == null || rule.getMatch() == null
                        || !"format_number".equals(rule.getOperation())
                        || rule.getParams() == null) {
                    continue;
                }
                MatchSpec match = rule.getMatch();
                boolean matches = path != null
                        ? path.equalsIgnoreCase(match.getPath())
                        : type.equalsIgnoreCase(match.getType());
                if (!matches) {
                    continue;
                }
                Double absolute = doubleValue(rule.getParams().get("tolerance"));
                Double relative = doubleValue(rule.getParams().get("relativeTolerance"));
                if (absolute == null && relative == null) {
                    continue;
                }
                return ColumnTolerance.builder()
                        .absolute(absolute)
                        .relative(relative)
                        .build();
            }
        }
        return null;
    }

    private static DataType columnType(Map<String, DataType> columnTypes, String column) {
        if (column == null || columnTypes == null) {
            return DataType.UNKNOWN;
        }
        return columnTypes.entrySet().stream()
                .filter(entry -> column.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DataType.UNKNOWN);
    }

    /** Maps a DataType onto the normalization registry's type-family names. */
    private static String typeFamily(DataType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                return "integer";
            case DECIMAL:
            case NUMERIC:
                return "decimal";
            case FLOAT:
            case DOUBLE:
            case REAL:
                return "float";
            default:
                return type.name().toLowerCase(Locale.ROOT);
        }
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number) {
            double parsed = ((Number) value).doubleValue();
            return parsed >= 0 ? parsed : null;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                double parsed = Double.parseDouble(((String) value).trim());
                return parsed >= 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
