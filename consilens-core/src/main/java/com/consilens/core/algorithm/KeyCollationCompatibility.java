package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 键列排序规则兼容性检查（P2-5）。
 *
 * <p>比对引擎在 Java 侧按字节比较键，而 SQL 侧的分组/等值比较走列的 collation。
 * 某些引擎默认使用大小写不敏感的排序规则（MySQL 系 {@code *_ci}、SQL Server 默认 CI），
 * 于是 {@code 'ABC'} 与 {@code 'abc'} 在 SQL 里属于同一组、在 Java 里是两个键：
 * 分段/校验和与本地比对的键归属会不一致。
 *
 * <p>这里能做的是"发现并告警"而不是自动改写 SQL：强制 binary collation 需要
 * 逐方言的 {@code COLLATE}/{@code BINARY} 语法与字符集信息，改错方向会把真实差异判成相等。
 */
@Slf4j
final class KeyCollationCompatibility {

    /** 默认排序规则大小写不敏感、且允许用户键出现大小写变体的引擎。 */
    private static final Set<String> CASE_INSENSITIVE_DEFAULT_ENGINES = Set.of(
            "mysql", "tidb", "doris", "starrocks", "oceanbase", "sqlserver");

    private KeyCollationCompatibility() {
    }

    static boolean engineDefaultsToCaseInsensitiveCollation(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            return false;
        }
        return CASE_INSENSITIVE_DEFAULT_ENGINES.contains(connectorType.trim().toLowerCase(Locale.ROOT));
    }

    /** @return 参与比较的字符串型主键列；没有 schema 时返回空列表（无法判断）。 */
    static List<String> stringKeyColumns(TableSegment table) {
        if (table == null || table.getSchema() == null || table.getSchema().isEmpty()
                || table.getKeyColumns() == null) {
            return List.of();
        }
        List<String> stringKeys = new ArrayList<>();
        for (String key : table.getKeyColumns()) {
            Optional<ColumnInfo> info = table.getSchema().get().getColumn(key);
            if (info.isPresent() && info.get().getType() != null && info.get().getType().isString()) {
                stringKeys.add(key);
            }
        }
        return stringKeys;
    }

    /**
     * 列上带 collation 元数据时以它为准；元数据缺失（当前多数 adapter 都留空）时回退到
     * 引擎默认排序规则的已知行为。
     */
    static boolean columnCollationIsCaseInsensitive(TableSegment table, String column) {
        if (table == null || table.getSchema() == null || table.getSchema().isEmpty()) {
            return false;
        }
        return table.getSchema().get().getColumn(column)
                .flatMap(ColumnInfo::getCollation)
                .map(KeyCollationCompatibility::isCaseInsensitiveCollation)
                .orElse(false);
    }

    static boolean columnCollationIsBinary(TableSegment table, String column) {
        if (table == null || table.getSchema() == null || table.getSchema().isEmpty()) {
            return false;
        }
        return table.getSchema().get().getColumn(column)
                .flatMap(ColumnInfo::getCollation)
                .map(KeyCollationCompatibility::isBinaryCollation)
                .orElse(false);
    }

    static boolean isCaseInsensitiveCollation(String collation) {
        if (collation == null || collation.isBlank()) {
            return false;
        }
        String normalized = collation.toUpperCase(Locale.ROOT);
        return normalized.contains("_CI") || normalized.contains("AI_CI") || normalized.contains("CI_AS");
    }

    static boolean isBinaryCollation(String collation) {
        if (collation == null || collation.isBlank()) {
            return false;
        }
        String normalized = collation.toUpperCase(Locale.ROOT);
        return normalized.contains("_BIN") || normalized.endsWith("BIN")
                || normalized.contains("_CS") || normalized.contains("CS_AS");
    }

    static void warnIfRisky(TableSegment table) {
        describeIfRisky(table).ifPresent(message -> log.warn(message));
    }

    /**
     * Same detection as {@link #warnIfRisky(TableSegment)} but returns the warning text
     * so callers can also expose it in a structured schema report.
     */
    static Optional<String> describeIfRisky(TableSegment table) {
        if (table == null || table.getDatabase() == null) {
            return Optional.empty();
        }
        List<String> stringKeys = stringKeyColumns(table);
        if (stringKeys.isEmpty()) {
            return Optional.empty();
        }
        boolean engineDefaultCaseInsensitive =
                engineDefaultsToCaseInsensitiveCollation(table.getDatabase().getConnectorType());
        if (!engineDefaultCaseInsensitive) {
            // 引擎默认大小写敏感时，只有列上显式声明了 CI 排序规则才需要告警
            if (stringKeys.stream().noneMatch(key -> columnCollationIsCaseInsensitive(table, key))) {
                return Optional.empty();
            }
        } else {
            // 引擎默认 CI：列上显式 binary/case-sensitive 的键可以排除
            stringKeys = stringKeys.stream()
                    .filter(key -> !columnCollationIsBinary(table, key))
                    .collect(java.util.stream.Collectors.toList());
            if (stringKeys.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(String.format(
                "Table %s compares string key column(s) %s on an engine whose default collation is "
                        + "case-insensitive (%s). SQL grouping/joins treat 'A' and 'a' as equal while this "
                        + "engine compares keys byte-wise, so keys that differ only in case can be grouped "
                        + "or attributed incorrectly. Use a binary/deterministic collation for these columns "
                        + "or normalize the key before comparing.",
                table.getTablePath(), stringKeys, table.getDatabase().getConnectorType()));
    }
}
