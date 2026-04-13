package com.consilens.ai.analyzer;

import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.AIAnalyzerProvider;

import java.util.Map;

/**
 * SPI provider for the rule-based analyzer.
 */
public class RuleBasedAnalyzerProvider implements AIAnalyzerProvider {

    @Override
    public String getName() {
        return "rulebased";
    }

    @Override
    public AIAnalyzer create() {
        return new RuleBasedAnalyzer();
    }

    @Override
    public AIAnalyzer create(Map<String, ?> config) {
        return new RuleBasedAnalyzer();
    }
}
