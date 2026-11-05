package com.consilens.core.exception;

import lombok.Getter;

/**
 * Thrown when a comparison exceeds an explicitly configured budget
 * (maxDurationMs / maxRowsScanned / maxBytesFetched). The partial result is
 * preserved by the caller; this never degrades into a silent "pass".
 */
@Getter
public class BudgetExceededException extends RuntimeException {

    private final String limitName;
    private final long limit;
    private final long actual;

    public BudgetExceededException(String limitName, long limit, long actual) {
        super("Budget exceeded: " + limitName + "=" + limit + " but actual=" + actual
                + ". Narrow the comparison scope or raise the budget; the result is "
                + "partial and must not be treated as a complete pass.");
        this.limitName = limitName;
        this.limit = limit;
        this.actual = actual;
    }

}
