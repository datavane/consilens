package com.consilens.conncetor.base.jdbc;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.UpdateWindow;
import com.consilens.connector.api.planner.KeyRangeSplit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WhereClauseBuilderTest {

    @Test
    void shouldMergePredicatesInStableOrder() {
        String startTimestamp = java.sql.Timestamp.from(Instant.parse("2026-05-03T09:00:00Z")).toString();
        String endTimestamp = java.sql.Timestamp.from(Instant.parse("2026-05-03T10:00:00Z")).toString();
        String whereClause = new WhereClauseBuilder(dialect())
                .addBaseFilter(PredicateSpec.builder().expression("status = 1").build())
                .addUpdateWindow(UpdateWindow.builder()
                        .column("updated_at")
                        .start(Instant.parse("2026-05-03T09:00:00Z"))
                        .end(Instant.parse("2026-05-03T10:00:00Z"))
                        .build())
                .addSplit(new KeyRangeSplit(List.of(1L), List.of(10L)), List.of("id"))
                .addKeyPredicate(List.of("id"), List.of(List.of(1L), List.of(2L)))
                .build();

        assertEquals(""
                        + "(status = 1) AND "
                        + "(`updated_at` >= '" + startTimestamp + "' AND `updated_at` < '" + endTimestamp + "') AND "
                        + "(`id` >= 1 AND `id` < 10) AND "
                        + "(`id` IN (1, 2))",
                whereClause);
    }

    private DatabaseDialect dialect() {
        CapabilityProvider capabilityProvider = (CapabilityProvider) Proxy.newProxyInstance(
                CapabilityProvider.class.getClassLoader(),
                new Class<?>[]{CapabilityProvider.class},
                (proxy, method, args) -> {
                    if ("quote".equals(method.getName())) {
                        return "`" + args[0] + "`";
                    }
                    return null;
                });
        SqlQueryGenerator sqlQueryGenerator = (SqlQueryGenerator) Proxy.newProxyInstance(
                SqlQueryGenerator.class.getClassLoader(),
                new Class<?>[]{SqlQueryGenerator.class},
                (proxy, method, args) -> {
                    if ("formatValue".equals(method.getName())) {
                        Object value = args[0];
                        if (value instanceof java.sql.Timestamp) {
                            return "'" + value + "'";
                        }
                        return value instanceof String ? "'" + value + "'" : String.valueOf(value);
                    }
                    return null;
                });
        return (DatabaseDialect) Proxy.newProxyInstance(
                DatabaseDialect.class.getClassLoader(),
                new Class<?>[]{DatabaseDialect.class},
                (proxy, method, args) -> {
                    if ("getCapabilityProvider".equals(method.getName())) {
                        return capabilityProvider;
                    }
                    if ("getSqlQueryGenerator".equals(method.getName())) {
                        return sqlQueryGenerator;
                    }
                    return null;
                });
    }
}
