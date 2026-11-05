package com.consilens.cli.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares two normalized contracts column by column.
 */
public final class ContractDiffer {

    private ContractDiffer() {
    }

    public static ContractDiff compare(DataContract source, DataContract target) {
        Map<String, ContractColumn> sourceColumns = source.columnMap();
        Map<String, ContractColumn> targetColumns = target.columnMap();
        Set<String> names = new LinkedHashSet<>(sourceColumns.keySet());
        names.addAll(targetColumns.keySet());

        List<ContractDiff.Change> changes = new ArrayList<>();
        for (String name : names) {
            ContractColumn left = sourceColumns.get(name);
            ContractColumn right = targetColumns.get(name);
            if (left == null) {
                changes.add(change(ContractDiff.ChangeKind.COLUMN_ADDED, name, null, type(right)));
                continue;
            }
            if (right == null) {
                changes.add(change(ContractDiff.ChangeKind.COLUMN_REMOVED, name, type(left), null));
                continue;
            }
            if (!sameType(left.effectiveType(), right.effectiveType())) {
                changes.add(change(ContractDiff.ChangeKind.TYPE_CHANGED, name,
                        type(left), type(right)));
            }
            if (Boolean.TRUE.equals(left.getRequired()) != Boolean.TRUE.equals(right.getRequired())) {
                changes.add(change(ContractDiff.ChangeKind.REQUIRED_CHANGED, name,
                        bool(left.getRequired()), bool(right.getRequired())));
            }
            if (Boolean.TRUE.equals(left.getUnique()) != Boolean.TRUE.equals(right.getUnique())) {
                changes.add(change(ContractDiff.ChangeKind.UNIQUE_CHANGED, name,
                        bool(left.getUnique()), bool(right.getUnique())));
            }
            if (Boolean.TRUE.equals(left.getPrimaryKey()) != Boolean.TRUE.equals(right.getPrimaryKey())) {
                changes.add(change(ContractDiff.ChangeKind.PRIMARY_KEY_CHANGED, name,
                        bool(left.getPrimaryKey()), bool(right.getPrimaryKey())));
            }
        }
        return ContractDiff.builder()
                .sourceName(displayName(source))
                .targetName(displayName(target))
                .identical(changes.isEmpty())
                .changes(changes)
                .build();
    }

    private static ContractDiff.Change change(ContractDiff.ChangeKind kind, String column,
                                              String sourceValue, String targetValue) {
        return ContractDiff.Change.builder()
                .kind(kind)
                .column(column)
                .sourceValue(sourceValue)
                .targetValue(targetValue)
                .build();
    }

    private static boolean sameType(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static String type(ContractColumn column) {
        return column == null ? null : column.effectiveType();
    }

    private static String bool(Boolean value) {
        return value == null ? null : value.toString();
    }

    private static String displayName(DataContract contract) {
        if (contract.getName() != null && !contract.getName().isBlank()) {
            return contract.getName();
        }
        return contract.getId() != null ? contract.getId() : "contract";
    }
}
