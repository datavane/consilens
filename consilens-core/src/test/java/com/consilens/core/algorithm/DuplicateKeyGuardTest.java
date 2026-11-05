package com.consilens.core.algorithm;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 严格档（validateUniqueKeys=true）下唯一键守卫的失败语义：
 * 探针执行失败必须明确报错（带上表名/根因），不得静默当作"没有重复键"。
 */
class DuplicateKeyGuardTest {

    @Test
    void shouldFailWithTableAndCauseWhenProbeCannotRunInStrictMode() {
        TableSegment table1 = table("source_orders");
        TableSegment table2 = table("target_orders");
        when(table1.getDatabase().findDuplicateKeys(table1))
                .thenThrow(new RuntimeException("probe SQL unsupported"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> DuplicateKeyGuard.assertUnique(table1, table2));

        assertTrue(error.getMessage().contains("source_orders"), error.getMessage());
        assertTrue(error.getMessage().contains("probe SQL unsupported"), error.getMessage());
        assertTrue(error.getMessage().contains("唯一性无法验证"), error.getMessage());
        assertTrue(error.getMessage().contains("validateUniqueKeys=false"), error.getMessage());
    }

    @Test
    void shouldReportDuplicateKeysWithTableNames() {
        TableSegment table1 = table("source_orders");
        TableSegment table2 = table("target_orders");
        when(table1.getDatabase().findDuplicateKeys(table1)).thenReturn(List.of("1, a => 2 行"));
        when(table2.getDatabase().findDuplicateKeys(table2)).thenReturn(List.of());

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> DuplicateKeyGuard.assertUnique(table1, table2));

        assertTrue(error.getMessage().contains("1, a => 2 行"), error.getMessage());
        assertTrue(error.getMessage().contains("source_orders"), error.getMessage());
    }

    @Test
    void shouldPassWhenNoDuplicateKeys() {
        TableSegment table1 = table("source_orders");
        TableSegment table2 = table("target_orders");
        when(table1.getDatabase().findDuplicateKeys(table1)).thenReturn(List.of());
        when(table2.getDatabase().findDuplicateKeys(table2)).thenReturn(List.of());

        DuplicateKeyGuard.assertUnique(table1, table2);
    }

    private TableSegment table(String name) {
        return TableSegment.builder()
                .database(mock(DatabaseAdapter.class))
                .tablePath(TablePath.of(name))
                .keyColumns(List.of("id"))
                .build();
    }
}
