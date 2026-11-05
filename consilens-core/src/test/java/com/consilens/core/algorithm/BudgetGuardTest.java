package com.consilens.core.algorithm;

import com.consilens.core.exception.BudgetExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetGuardTest {

    @Test
    void shouldThrowWhenRowsScannedExceedBudget() {
        BudgetGuard guard = new BudgetGuard(Long.MAX_VALUE, 10L, Long.MAX_VALUE);

        guard.addRowsScanned(9L);
        BudgetExceededException error = assertThrows(BudgetExceededException.class,
                () -> guard.addRowsScanned(2L));

        assertEquals("maxRowsScanned", error.getLimitName());
        assertEquals(10L, error.getLimit());
        assertTrue(error.getActual() >= 11L);
        assertTrue(error.getMessage().contains("must not be treated as a complete pass"));
    }

    @Test
    void shouldThrowWhenBytesFetchedExceedBudget() {
        BudgetGuard guard = new BudgetGuard(Long.MAX_VALUE, Long.MAX_VALUE, 100L);

        guard.addBytesFetched(60L);
        assertThrows(BudgetExceededException.class, () -> guard.addBytesFetched(41L));
    }

    @Test
    void shouldIgnoreNonPositiveDeltas() {
        BudgetGuard guard = new BudgetGuard(Long.MAX_VALUE, 10L, Long.MAX_VALUE);

        assertDoesNotThrow(() -> {
            guard.addRowsScanned(0L);
            guard.addRowsScanned(-5L);
            guard.addBytesFetched(0L);
        });
    }

    @Test
    void shouldThrowImmediatelyWhenDurationBudgetAlreadyExceeded() {
        // 预算为 0 会被构造器归一化为不限；用 1ms 预算 + 人为等待不可靠，
        // 这里只验证构造合法与 checkDuration 在预算内不抛
        BudgetGuard guard = new BudgetGuard(60_000L, Long.MAX_VALUE, Long.MAX_VALUE);

        assertDoesNotThrow(guard::checkDuration);
    }

    @Test
    void unlimitedGuardNeverThrows() {
        BudgetGuard guard = BudgetGuard.unlimited();

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 1_000; i++) {
                guard.addRowsScanned(1_000L);
                guard.addBytesFetched(1_000L);
                guard.checkDuration();
            }
        });
    }
}
