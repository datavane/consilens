package com.consilens.sdk.verify;

/**
 * CI verdict for a verify run.
 */
public enum Verdict {
    PASS,
    FAIL,
    INCONCLUSIVE,
    BUDGET_EXCEEDED,
    ERROR;

    /** CLI exit code contract; keep stable for CI integrations. */
    public int exitCode() {
        switch (this) {
            case PASS:
                return 0;
            case FAIL:
                return 1;
            case INCONCLUSIVE:
                return 3;
            case BUDGET_EXCEEDED:
                return 4;
            case ERROR:
            default:
                return 2;
        }
    }
}
