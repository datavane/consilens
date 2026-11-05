package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.connector.mysql.MySQLDataTypeHandler;
import com.consilens.connector.postgresql.PostgreSQLDataTypeHandler;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL YEAR arrives as {@link java.sql.Date} while the PostgreSQL equivalent
 * (SMALLINT) arrives as an integer. The dialect handler must normalize the
 * MySQL representation so the two sides compare equal.
 */
class LocalDiffEngineYearNormalizationTest {

    @Test
    void shouldCompareMySqlYearDateAgainstPostgresInteger() {
        List<String> keys = List.of("id");
        List<String> extras = List.of("year_col");
        Map<String, DataType> mySqlTypes = Map.of("id", DataType.INTEGER, "year_col", DataType.INTEGER);
        Map<String, DataType> postgresTypes = Map.of("id", DataType.INTEGER, "year_col", DataType.SMALLINT);

        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                List.<Object[]>of(new Object[]{1, Date.valueOf("2092-01-01")}),
                List.<Object[]>of(new Object[]{1, 2092}),
                keys, extras, keys, extras,
                mySqlTypes, postgresTypes,
                new MySQLDataTypeHandler(null),
                new PostgreSQLDataTypeHandler(null));

        assertTrue(differences.isEmpty(), "YEAR values representing the same year must compare equal");
    }
}
