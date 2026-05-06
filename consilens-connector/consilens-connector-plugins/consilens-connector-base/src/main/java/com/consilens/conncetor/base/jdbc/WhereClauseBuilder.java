package com.consilens.conncetor.base.jdbc;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.SegmentSplit;

import java.util.ArrayList;
import java.util.List;

public final class WhereClauseBuilder {

    private final DatabaseDialect dialect;
    private final List<String> predicates = new ArrayList<>();

    public WhereClauseBuilder(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    public WhereClauseBuilder addBaseFilter(PredicateSpec filter) {
        if (filter != null && filter.getExpression() != null && !filter.getExpression().trim().isEmpty()) {
            predicates.add("(" + filter.getExpression().trim() + ")");
        }
        return this;
    }

    public WhereClauseBuilder addSplit(SegmentSplit split, List<String> keyColumns) {
        if (split == null) {
            return this;
        }
        if (split instanceof KeyRangeSplit) {
            predicates.add("(" + buildKeyRangePredicate((KeyRangeSplit) split, keyColumns) + ")");
            return this;
        }
        return this;
    }

    public WhereClauseBuilder addKeyPredicate(List<String> keyColumns, List<List<Object>> keys) {
        if (keyColumns == null || keyColumns.isEmpty() || keys == null || keys.isEmpty()) {
            return this;
        }
        if (keyColumns.size() == 1) {
            List<String> values = new ArrayList<>();
            for (List<Object> key : keys) {
                if (key != null && !key.isEmpty()) {
                    values.add(dialect.getSqlQueryGenerator().formatValue(key.get(0)));
                }
            }
            predicates.add("(" + quote(keyColumns.get(0)) + " IN (" + String.join(", ", values) + "))");
            return this;
        }

        List<String> disjunction = new ArrayList<>();
        for (List<Object> key : keys) {
            if (key == null || key.size() != keyColumns.size()) {
                continue;
            }
            List<String> conjunction = new ArrayList<>();
            for (int i = 0; i < keyColumns.size(); i++) {
                conjunction.add(quote(keyColumns.get(i)) + " = " + dialect.getSqlQueryGenerator().formatValue(key.get(i)));
            }
            disjunction.add("(" + String.join(" AND ", conjunction) + ")");
        }
        if (!disjunction.isEmpty()) {
            predicates.add("(" + String.join(" OR ", disjunction) + ")");
        }
        return this;
    }

    public String build() {
        return predicates.isEmpty() ? null : String.join(" AND ", predicates);
    }

    private String buildKeyRangePredicate(KeyRangeSplit split, List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new ConnectorException("Key range split requires key columns");
        }
        if (keyColumns.size() != 1) {
            throw new ConnectorException("Composite key range splits are not supported yet");
        }
        String column = quote(keyColumns.get(0));
        List<String> range = new ArrayList<>();
        if (split.getStartKey() != null && !split.getStartKey().isEmpty()) {
            range.add(column + " >= " + dialect.getSqlQueryGenerator().formatValue(split.getStartKey().get(0)));
        }
        if (split.getEndKey() != null && !split.getEndKey().isEmpty()) {
            range.add(column + " < " + dialect.getSqlQueryGenerator().formatValue(split.getEndKey().get(0)));
        }
        return String.join(" AND ", range);
    }

    private String quote(String identifier) {
        return dialect.getCapabilityProvider().quote(identifier);
    }
}
