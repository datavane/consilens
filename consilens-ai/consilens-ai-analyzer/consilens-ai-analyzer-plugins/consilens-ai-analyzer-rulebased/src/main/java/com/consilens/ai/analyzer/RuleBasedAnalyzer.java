package com.consilens.ai.analyzer;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Rule-based diff analyzer that applies heuristic patterns to detect data inconsistency causes.
 */
@Slf4j
public class RuleBasedAnalyzer implements AIAnalyzer {

    private final List<DiffPattern> patterns;

    public RuleBasedAnalyzer() {
        this.patterns = Arrays.asList(
                new TimeDriftPattern(),
                new PrecisionLossPattern(),
                new EncodingPattern(),
                new NullHandlingPattern(),
                new TruncationPattern(),
                new TimeWindowPattern()
        );
    }

    @Override
    public AnalysisResult analyze(DiffResult diffResult) {
        if (diffResult == null || diffResult.getDifferences() == null || diffResult.getDifferences().isEmpty()) {
            return AnalysisResult.builder()
                    .summary("No differences found to analyze.")
                    .confidence(1.0)
                    .build();
        }

        List<DiffRow> rows = diffResult.getDifferences();
        List<PatternMatch> matches = patterns.stream()
                .map(p -> {
                    try {
                        return p.detect(rows);
                    } catch (Exception e) {
                        log.warn("Pattern {} threw exception: {}", p.getName(), e.getMessage());
                        return Optional.<PatternMatch>empty();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        String summary = generateSummary(diffResult, matches);
        double confidence = calculateConfidence(matches);
        List<String> repairHints = generateRepairHints(matches);

        return AnalysisResult.builder()
                .patterns(matches)
                .summary(summary)
                .confidence(confidence)
                .repairHints(repairHints)
                .build();
    }

    @Override
    public String explainResult(DiffResult diffResult) {
        if (diffResult == null) {
            return "No diff result provided.";
        }
        AnalysisResult analysis = analyze(diffResult);
        StringBuilder sb = new StringBuilder();
        sb.append("## Diff Analysis\n\n");
        sb.append(analysis.getSummary()).append("\n\n");

        if (!analysis.getPatterns().isEmpty()) {
            sb.append("### Detected Patterns\n");
            for (PatternMatch m : analysis.getPatterns()) {
                sb.append(String.format("- **%s** (confidence: %.0f%%): %s\n",
                        m.getPatternName(), m.getConfidence() * 100, m.getDescription()));
            }
            sb.append("\n");
        }

        if (!analysis.getRepairHints().isEmpty()) {
            sb.append("### Repair Suggestions\n");
            for (String hint : analysis.getRepairHints()) {
                sb.append("- ").append(hint).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public String getName() {
        return "rulebased";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private String generateSummary(DiffResult diffResult, List<PatternMatch> matches) {
        int total = diffResult.getDifferences().size();
        if (matches.isEmpty()) {
            return String.format("Found %d differences. No specific patterns detected.", total);
        }
        String patternNames = matches.stream()
                .map(PatternMatch::getPatternName)
                .collect(Collectors.joining(", "));
        return String.format("Found %d differences. Detected %d pattern(s): %s.", total, matches.size(), patternNames);
    }

    private double calculateConfidence(List<PatternMatch> matches) {
        if (matches.isEmpty()) {
            return 0.5;
        }
        return matches.stream()
                .mapToDouble(PatternMatch::getConfidence)
                .average()
                .orElse(0.5);
    }

    private List<String> generateRepairHints(List<PatternMatch> matches) {
        return matches.stream()
                .map(PatternMatch::getRepairHint)
                .filter(h -> h != null && !h.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }
}
