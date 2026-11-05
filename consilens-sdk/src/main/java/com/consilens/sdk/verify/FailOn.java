package com.consilens.sdk.verify;

/**
 * What should turn differences into a FAIL verdict.
 */
public enum FailOn {
    /** Any difference fails. */
    ANY,
    /** Only differences that are not present in the baseline fail. Requires a baseline. */
    NEW,
    /** Report-only: never fail on differences. */
    NONE
}
