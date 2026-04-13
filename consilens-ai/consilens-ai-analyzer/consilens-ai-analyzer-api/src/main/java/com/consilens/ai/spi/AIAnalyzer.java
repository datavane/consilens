package com.consilens.ai.spi;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.core.diff.DiffResult;

/**
 * SPI interface for AI-powered diff analyzers.
 */
public interface AIAnalyzer {

    /**
     * Analyzes the given diff result and returns an analysis with patterns and suggestions.
     *
     * @param diffResult the diff result to analyze
     * @return the analysis result
     */
    AnalysisResult analyze(DiffResult diffResult);

    /**
     * Generates a human-readable explanation of the diff result.
     *
     * @param diffResult the diff result to explain
     * @return a plain-text explanation
     */
    String explainResult(DiffResult diffResult);

    /**
     * Returns the name of this analyzer.
     */
    String getName();

    /**
     * Returns {@code true} if this analyzer is available and ready to use.
     */
    boolean isAvailable();
}
