package com.consilens.connector.api.normalization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把“按类型配置”的归一化规则（{@code decimal: {precision: 6}}）编译成
 * {@link NormalizationSpec}，供 CLI 与 server 共用。
 *
 * <p>两侧共用同一实现的原因：归一化契约必须与 SQL 生成、本地比对完全一致，
 * 一旦 CLI 与 server 各自解释配置，同一份定义在两种入口下会得到不同结论。
 */
public final class NormalizationSpecs {

    private NormalizationSpecs() {
    }

    /**
     * @param global 全局规则，key 为类型名，value 为该类型的字段集合
     * @param source 仅源端生效的规则（覆盖 global）
     * @param target 仅目标端生效的规则（覆盖 global）
     * @return 空规则时返回 null，便于 {@code CompareRequest} 区分“未配置”
     */
    public static NormalizationSpec fromTypeRules(Map<String, Map<String, Object>> global,
                                                  Map<String, Map<String, Object>> source,
                                                  Map<String, Map<String, Object>> target) {
        List<NormalizationRule> globalRules = toRules(global);
        List<NormalizationRule> sourceRules = toRules(source);
        List<NormalizationRule> targetRules = toRules(target);
        if (globalRules == null && sourceRules == null && targetRules == null) {
            return null;
        }
        return NormalizationSpec.builder()
                .global(globalRules)
                .source(sourceRules)
                .target(targetRules)
                .build();
    }

    static List<NormalizationRule> toRules(Map<String, Map<String, Object>> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        List<NormalizationRule> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : rules.entrySet()) {
            String canonicalType = NormalizationTypeMapper.toMatchType(entry.getKey());
            Map<String, Object> rule = entry.getValue();
            if (canonicalType == null || rule == null) {
                continue;
            }
            result.addAll(toRules(canonicalType, rule));
        }
        return result.isEmpty() ? null : result;
    }

    static List<NormalizationRule> toRules(String type, Map<String, Object> rule) {
        List<NormalizationRule> result = new ArrayList<>();
        Integer precision = integer(rule.get("precision"));
        Boolean rounding = bool(rule.get("rounding"));
        // 数值容差：绝对值 / 相对值。容忍列在 checksum 候选回查与本地比较中生效；
        // SQL 侧校验和无法容忍（值不同哈希必不同），容差列由递归收敛到终局比较兜底。
        Double tolerance = doubleValue(rule.get("tolerance"));
        Double relativeTolerance = doubleValue(rule.get("relativeTolerance"));
        String format = string(rule.get("format"));
        String timezone = string(rule.get("timezone"));
        String comparisonMode = string(rule.get("comparisonMode"));
        // 会话时区是"连接级"信息，但按类型配置语法只能挂在某个类型的规则上；
        // 只要出现在任一规则里，core 侧就会读取它（见 CompareExecutionSettings.resolveSessionZone）。
        String sessionTimeZone = string(rule.get("sessionTimeZone"));
        if (sessionTimeZone == null) {
            sessionTimeZone = string(rule.get("session_time_zone"));
        }
        if (sessionTimeZone != null) {
            // 时区写错不能让 core 静默当成"没配置"：那样两侧会用不同时区归一化，差异永远对不上
            try {
                java.time.ZoneId.of(sessionTimeZone.trim());
            } catch (java.time.DateTimeException exception) {
                throw new IllegalArgumentException("sessionTimeZone must be an IANA zone or UTC offset, got: "
                        + sessionTimeZone, exception);
            }
        }

        if (precision != null || rounding != null || tolerance != null || relativeTolerance != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (precision != null) {
                params.put("precision", precision);
            }
            if (rounding != null) {
                params.put("rounding", rounding);
            }
            if (tolerance != null) {
                params.put("tolerance", tolerance);
            }
            if (relativeTolerance != null) {
                params.put("relativeTolerance", relativeTolerance);
            }
            result.add(rule(type, "format_number", params));
        }

        if (format != null || timezone != null || comparisonMode != null || sessionTimeZone != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (format != null) {
                params.put("format", format);
            }
            if (timezone != null) {
                params.put("timezone", timezone);
            }
            if (comparisonMode != null) {
                params.put("comparisonMode", comparisonMode);
            }
            if (sessionTimeZone != null) {
                params.put("sessionTimeZone", sessionTimeZone);
            }
            result.add(rule(type, "format_datetime", params));
        }

        String encoding = string(rule.get("encoding"));
        Boolean uppercase = bool(rule.get("uppercase"));
        if (encoding != null || uppercase != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (encoding != null) {
                params.put("encoding", encoding);
            }
            if (uppercase != null) {
                params.put("uppercase", uppercase);
            }
            result.add(rule(type, "encode", params));
        }

        String trueValue = string(rule.get("trueValue"));
        String falseValue = string(rule.get("falseValue"));
        String nullValue = string(rule.get("nullValue"));
        if ("boolean".equals(type) && (trueValue != null || falseValue != null || nullValue != null)) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (trueValue != null) {
                params.put("trueValue", trueValue);
            }
            if (falseValue != null) {
                params.put("falseValue", falseValue);
            }
            if (nullValue != null) {
                params.put("nullValue", nullValue);
            }
            result.add(rule(type, "map_boolean", params));
        } else if ("string".equals(type) && nullValue != null) {
            result.add(rule(type, "normalize_string", Map.of("nullValue", nullValue)));
        }
        return result;
    }

    private static NormalizationRule rule(String type, String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().type(type).build())
                .operation(operation)
                .params(params)
                .build();
    }

    /** 列级规则：{@code match.path} 指向列名，优先级高于类型级规则（见引擎侧解析顺序）。 */
    private static NormalizationRule pathRule(String column, String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().path(column).build())
                .operation(operation)
                .params(params)
                .build();
    }

    /**
     * 把"按列配置"的规则（{@code columns: {amount: {precision: 6}}}）编译成
     * {@code MatchSpec.path} 规则。编译结果与类型级规则合并在同一个 spec 里，
     * 引擎侧解析时列级优先。
     */
    public static NormalizationSpec withColumnRules(NormalizationSpec base,
                                                    Map<String, Map<String, Object>> columns) {
        List<NormalizationRule> columnRules = toColumnRules(columns);
        if (columnRules == null && base == null) {
            return null;
        }
        return NormalizationSpec.builder()
                .global(mergeRules(base == null ? null : base.getGlobal(), columnRules))
                .source(base == null ? null : base.getSource())
                .target(base == null ? null : base.getTarget())
                .build();
    }

    private static List<NormalizationRule> mergeRules(List<NormalizationRule> existing,
                                                      List<NormalizationRule> columnRules) {
        List<NormalizationRule> merged = new ArrayList<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (columnRules != null) {
            merged.addAll(columnRules);
        }
        return merged.isEmpty() ? null : merged;
    }

    public static List<NormalizationRule> toColumnRules(Map<String, Map<String, Object>> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        List<NormalizationRule> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : columns.entrySet()) {
            String column = entry.getKey();
            Map<String, Object> rule = entry.getValue();
            if (column == null || column.isBlank() || rule == null || rule.isEmpty()) {
                continue;
            }
            // 列级规则当前只开放数值容差。precision/timezone 等需要 SQL 侧同步实现，
            // 未打穿前不接受，避免本地与校验和口径分叉（validator 会拒绝其它参数）。
            Double tolerance = doubleValue(rule.get("tolerance"));
            Double relativeTolerance = doubleValue(rule.get("relativeTolerance"));
            if (tolerance != null || relativeTolerance != null) {
                Map<String, Object> params = new LinkedHashMap<>();
                if (tolerance != null) {
                    params.put("tolerance", tolerance);
                }
                if (relativeTolerance != null) {
                    params.put("relativeTolerance", relativeTolerance);
                }
                result.add(pathRule(column, "format_number", params));
            }
        }
        return result.isEmpty() ? null : result;
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

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return null;
    }
}
