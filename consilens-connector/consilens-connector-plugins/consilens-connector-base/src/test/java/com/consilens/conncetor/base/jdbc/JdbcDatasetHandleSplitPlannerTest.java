package com.consilens.conncetor.base.jdbc;

import com.consilens.conncetor.base.AbstractDatabaseDialect;
import com.consilens.conncetor.base.BaseCapabilityProvider;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.SplitOptions;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.SegmentSplit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcDatasetHandleSplitPlannerTest {

    @Test
    void shouldCreateOrderedNonOverlappingRangesForFilteredNumericTable() throws Exception {
        String url = createDatabase("ranges", ""
                + "INSERT INTO \"orders\"(\"id\", \"status\") VALUES (1, 1), (5, 1), (9, 1), (99, 0)");
        try (JdbcDatasetHandle handle = createHandle(url)) {
            CompareSegment segment = segment("id", DataType.BIGINT, "\"status\" = 1");

            List<SegmentSplit> splits = handle.getSplitPlanner().orElseThrow()
                    .split(segment, SplitOptions.builder().expectedSplitCount(2).build());

            assertEquals(2, splits.size());
            KeyRangeSplit first = (KeyRangeSplit) splits.get(0);
            KeyRangeSplit second = (KeyRangeSplit) splits.get(1);
            assertEquals(new BigDecimal("1"), first.getStartKey().get(0));
            assertEquals(new BigDecimal("5.0000000000000000"), first.getEndKey().get(0));
            assertEquals(first.getEndKey(), second.getStartKey());
            assertNull(second.getEndKey());
        }
    }

    @Test
    void shouldReturnNoRangesForEmptyTable() throws Exception {
        String url = createDatabase("empty", null);
        try (JdbcDatasetHandle handle = createHandle(url)) {
            List<SegmentSplit> splits = handle.getSplitPlanner().orElseThrow()
                    .split(segment("id", DataType.BIGINT, null), SplitOptions.builder().targetRowsPerSplit(10L).build());

            assertEquals(List.of(), splits);
        }
    }

    @Test
    void shouldRejectUnsupportedKeysAndInvalidOptions() throws Exception {
        String url = createDatabase("invalid", "INSERT INTO \"orders\"(\"id\", \"status\", \"code\") VALUES (1, 1, 'a')");
        try (JdbcDatasetHandle handle = createHandle(url)) {
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("code", DataType.VARCHAR, null), SplitOptions.builder().expectedSplitCount(1).build()));
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("id\" OR 1 = 1 --", DataType.BIGINT, null), SplitOptions.builder().expectedSplitCount(1).build()));
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("id", DataType.BIGINT, null), SplitOptions.builder().expectedSplitCount(0).build()));
        }
    }

    @Test
    void shouldRejectFloatingPointKeys() throws Exception {
        String url = createDatabase("floating", ""
                + "INSERT INTO \"orders\"(\"id\", \"float_key\", \"double_key\", \"real_key\") "
                + "VALUES (1, 1.0, 1.0, 1.0)");
        try (JdbcDatasetHandle handle = createHandle(url)) {
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("float_key", DataType.FLOAT, null), SplitOptions.builder().expectedSplitCount(1).build()));
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("double_key", DataType.DOUBLE, null), SplitOptions.builder().expectedSplitCount(1).build()));
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("real_key", DataType.REAL, null), SplitOptions.builder().expectedSplitCount(1).build()));
        }
    }

    @Test
    void shouldRejectNullKeys() throws Exception {
        String url = createDatabase("null_key", "INSERT INTO \"orders\"(\"id\") VALUES (NULL)");
        try (JdbcDatasetHandle handle = createHandle(url)) {
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("id", DataType.BIGINT, null), SplitOptions.builder().expectedSplitCount(1).build()));
        }
    }

    @Test
    void shouldRejectSqlResource() throws Exception {
        String url = createDatabase("sql_resource", "INSERT INTO \"orders\"(\"id\") VALUES (1)");
        ResourceLocator sqlResource = ResourceLocator.builder()
                .type("sql")
                .path("SELECT \"id\" FROM \"orders\"")
                .build();
        try (JdbcDatasetHandle handle = createHandle(url, sqlResource)) {
            assertThrows(ConnectorException.class, () -> handle.getSplitPlanner().orElseThrow()
                    .split(segment("id", DataType.BIGINT, null), SplitOptions.builder().expectedSplitCount(1).build()));
        }
    }

    @Test
    void shouldSupportDecimalKeys() throws Exception {
        String url = createDatabase("decimal", ""
                + "INSERT INTO \"orders\"(\"decimal_key\") VALUES (1.00), (5.00), (9.00)");
        try (JdbcDatasetHandle handle = createHandle(url)) {
            List<SegmentSplit> splits = handle.getSplitPlanner().orElseThrow()
                    .split(segment("decimal_key", DataType.DECIMAL, null), SplitOptions.builder().expectedSplitCount(2).build());

            assertEquals(2, splits.size());
            assertEquals(new BigDecimal("1.00"), ((KeyRangeSplit) splits.get(0)).getStartKey().get(0));
        }
    }

    private String createDatabase(String name, String dataSql) throws Exception {
        String url = "jdbc:h2:mem:jdbc_split_" + name + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"orders\" ("
                    + "\"id\" BIGINT, \"status\" INTEGER, \"code\" VARCHAR(32), "
                    + "\"float_key\" FLOAT, \"double_key\" DOUBLE, \"real_key\" REAL, "
                    + "\"decimal_key\" DECIMAL(10, 2))");
            if (dataSql != null) {
                statement.execute(dataSql);
            }
        }
        return url;
    }

    private JdbcDatasetHandle createHandle(String url) {
        return createHandle(url, ResourceLocator.builder().type("table").name("orders").build());
    }

    private JdbcDatasetHandle createHandle(String url, ResourceLocator resource) {
        return new JdbcDatasetHandle(
                "h2",
                "h2",
                ignored -> dialect(),
                Map.of("url", url),
                resource,
                ReadOptions.builder().build());
    }

    private CompareSegment segment(String keyColumn, DataType keyType, String filter) {
        FieldDescriptor keyField = FieldDescriptor.builder()
                .name(keyColumn)
                .canonicalType(keyType.name())
                .build();
        return CompareSegment.builder()
                .keySpec(KeySpec.builder().fields(List.of(keyColumn)).build())
                .schema(SchemaDescriptor.builder().fields(List.of(keyField)).build())
                .filter(filter == null ? null : PredicateSpec.builder().expression(filter).build())
                .build();
    }

    private DatabaseDialect dialect() {
        return new AbstractDatabaseDialect() {
            private final CapabilityProvider capabilityProvider = new BaseCapabilityProvider();
            private final SqlQueryGenerator sqlQueryGenerator = new BaseSqlQueryGenerator(capabilityProvider) {
                @Override
                public String getRowHashSQL(String schemaName, String tableName, List<String> primaryKeys,
                        List<String> columns, Map<String, DataType> columnDataTypes, String whereClause) {
                    return "";
                }
            };

            @Override
            public String getConnectorType() {
                return "h2";
            }

            @Override
            public CapabilityProvider getCapabilityProvider() {
                return capabilityProvider;
            }

            @Override
            public SqlQueryGenerator getSqlQueryGenerator() {
                return sqlQueryGenerator;
            }
        };
    }
}
