package com.consilens.ai.spi;

import com.consilens.ai.model.PatternMatch;
import com.consilens.core.diff.DiffRow;

import java.util.List;
import java.util.Optional;

/**
 * SPI interface for diff pattern detectors.
 */
public interface DiffPattern {

    /**
     * Attempts to detect this pattern in the given list of diff rows.
     *
     * @param rows the diff rows to inspect
     * @return an {@link Optional} containing a match if the pattern was detected
     */
    Optional<PatternMatch> detect(List<DiffRow> rows);

    /**
     * Returns the unique name of this pattern.
     */
    String getName();

    /**
     * Returns a brief description of what this pattern detects.
     */
    String getDescription();
}
