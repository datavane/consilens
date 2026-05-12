package com.consilens.cli.ai;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds deterministic explanation text from the existing CLI configuration model.
 */
public class AIExplainService {

    public String explain(CliConfiguration config) {
        List<String> risks = risks(config);
        StringBuilder builder = new StringBuilder();
        builder.append("# AI Explain").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Source:").append(System.lineSeparator())
                .append("  ").append(dataset(config.getSource())).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Target:").append(System.lineSeparator())
                .append("  ").append(dataset(config.getTarget())).append(System.lineSeparator()).append(System.lineSeparator());
        ComparisonConfig comparison = config.getComparison();
        builder.append("Keys:").append(System.lineSeparator())
                .append("  source ").append(comparison.getKeys().getSource())
                .append(" -> target ").append(comparison.getKeys().getTarget())
                .append(System.lineSeparator()).append(System.lineSeparator());
        if (comparison.getFields() != null && !comparison.getFields().isBothEmpty()) {
            builder.append("Fields:").append(System.lineSeparator())
                    .append("  source ").append(comparison.getFields().getSource())
                    .append(" -> target ").append(comparison.getFields().getTarget())
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append("Strategy:").append(System.lineSeparator())
                .append("  ").append(config.getStrategyMode()).append(" ")
                .append(config.getAlgorithm())
                .append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Result:").append(System.lineSeparator())
                .append("  sinks=").append(config.getResult() == null ? 0 : config.getResult().getSinks().size())
                .append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Risks:").append(System.lineSeparator());
        if (risks.isEmpty()) {
            builder.append("  - No obvious configuration risk found.").append(System.lineSeparator());
        } else {
            risks.forEach(risk -> builder.append("  - ").append(risk).append(System.lineSeparator()));
        }
        builder.append(System.lineSeparator());
        builder.append("Recommendations:").append(System.lineSeparator())
                .append("  - Run `consilens diff --dry-run -c <config>` before executing a real diff.")
                .append(System.lineSeparator())
                .append("  - Keep credentials in environment variables, not plaintext YAML.")
                .append(System.lineSeparator());
        return builder.toString();
    }

    private String dataset(ConnectionConfig config) {
        ConnectionConfig.ResourceConfig resource = config.getResource();
        String location = "sql".equalsIgnoreCase(resource.getType()) ? resource.getPath() : resource.getName();
        return config.getType() + " " + value(config.getName()) + " " + resource.getType() + ":" + location;
    }

    private List<String> risks(CliConfiguration config) {
        List<String> risks = new ArrayList<>();
        if (config.getComparison().getFields() == null || config.getComparison().getFields().isBothEmpty()) {
            risks.add("No explicit comparison fields configured; engine behavior depends on connector metadata.");
        }
        if (config.getSource().getPassword() != null && !config.getSource().getPassword().startsWith("${env.")) {
            risks.add("Source password is not an environment placeholder.");
        }
        if (config.getTarget().getPassword() != null && !config.getTarget().getPassword().startsWith("${env.")) {
            risks.add("Target password is not an environment placeholder.");
        }
        if (config.getStrategy() != null && config.getStrategy().getMaxDifferences() != null
                && config.getStrategy().getMaxDifferences() < 1000) {
            risks.add("maxDifferences is low; large comparisons may stop before producing useful samples.");
        }
        return risks;
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "(unnamed)" : value;
    }
}
