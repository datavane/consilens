package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.validation.ValidationException;

import java.util.Objects;

/**
 * Centralized configuration normalization and validation.
 * <p>
 * This keeps CLI loading lightweight and ensures defaults + validation are
 * consistently applied in one place.
 */
public class ConfigNormalizer {

    /**
     * Normalize configuration values and validate.
     */
    public CliConfiguration normalize(CliConfiguration config) throws ValidationException {
        Objects.requireNonNull(config, "Configuration cannot be null");

        applyDefaultsAndProperties(config);

        config.validate();
        return config;
    }

    /**
     * Apply defaults and initialize properties map.
     */
    private void applyDefaultsAndProperties(CliConfiguration config) {
        StrategyConfig strategy = config.getStrategy();
        if (strategy == null) {
            strategy = StrategyConfig.builder().build();
            config.setStrategy(strategy);
        }

        if (strategy.getMode() == null || strategy.getMode().trim().isEmpty()) {
            strategy.setMode("checksum");
        }
        if (strategy.getAlgorithm() == null || strategy.getAlgorithm().trim().isEmpty()) {
            strategy.setAlgorithm("concat");
        }
        if (strategy.getBatchSize() == null || strategy.getBatchSize() <= 0) {
            strategy.setBatchSize(1000);
        }
        if (strategy.getBisectionFactor() == null || strategy.getBisectionFactor() <= 0) {
            strategy.setBisectionFactor(4);
        }
        if (strategy.getEnableProfiling() == null) {
            strategy.setEnableProfiling(false);
        }

        if (config.getConcurrency() == null) {
            config.setConcurrency(ConcurrencyConfig.defaultConfig());
        }
    }
}
