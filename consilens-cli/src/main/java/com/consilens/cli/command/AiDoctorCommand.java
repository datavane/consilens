package com.consilens.cli.command;

import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.AIAnalyzerManager;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendManager;
import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.cli.ai.LLMBackendResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs offline production-readiness checks for AI command wiring.
 */
@Command(
    name = "doctor",
    description = "Check AI providers, selected analyzer, backend wiring and required credentials",
    mixinStandardHelpOptions = true
)
public class AiDoctorCommand implements Callable<Integer> {

    private static final String DEFAULT_ANALYZER = "rulebased";

    private final Supplier<Set<String>> analyzerNames;
    private final Supplier<Set<String>> backendNames;
    private final Function<String, AIAnalyzer> analyzerFactory;
    private final Function<AIBackendOptions, LLMBackend> backendFactory;
    private final Function<AIBackendOptions, String> backendNameResolver;
    private final Function<String, String> envProvider;
    private final ObjectMapper jsonMapper;

    @Option(names = "--analyzer", description = "Analyzer provider name. Defaults to CONSILENS_AI_ANALYZER or rulebased")
    private String analyzer;

    @Option(names = "--backend", description = "AI backend: noop, ollama, openai, deepseek. Defaults to CONSILENS_AI_BACKEND or noop")
    private String backend;

    @Option(names = "--model", description = "AI model name")
    private String model;

    @Option(names = "--base-url", description = "AI backend base URL")
    private String baseUrl;

    @Option(names = "--api-key", description = "AI backend API key")
    private String apiKey;

    @Option(names = "--timeout", description = "AI backend timeout")
    private String timeout;

    @Option(names = "--temperature", description = "AI sampling temperature")
    private Double temperature;

    @Option(names = "--max-tokens", description = "AI max output tokens")
    private Integer maxTokens;

    @Option(names = "--online", description = "Also call backend availability check. Default checks are offline only.")
    private boolean online;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json (default: text)")
    private String format;

    @Spec
    private CommandSpec spec;

    public AiDoctorCommand() {
        this(
                () -> AIAnalyzerManager.getInstance().supportedNames(),
                () -> LLMBackendManager.getInstance().supportedNames(),
                name -> AIAnalyzerManager.getInstance().create(name),
                AiDoctorCommand::resolveBackend,
                AiDoctorCommand::resolveBackendName,
                System::getenv,
                new ObjectMapper()
        );
    }

    AiDoctorCommand(Supplier<Set<String>> analyzerNames,
                    Supplier<Set<String>> backendNames,
                    Function<String, AIAnalyzer> analyzerFactory,
                    Function<AIBackendOptions, LLMBackend> backendFactory,
                    Function<AIBackendOptions, String> backendNameResolver,
                    Function<String, String> envProvider,
                    ObjectMapper jsonMapper) {
        this.analyzerNames = analyzerNames;
        this.backendNames = backendNames;
        this.analyzerFactory = analyzerFactory;
        this.backendFactory = backendFactory;
        this.backendNameResolver = backendNameResolver;
        this.envProvider = envProvider;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Integer call() throws Exception {
        DoctorReport report = runChecks();
        if ("json".equalsIgnoreCase(format)) {
            printJson(report);
        } else if ("text".equalsIgnoreCase(format)) {
            printText(report);
        } else {
            spec.commandLine().getErr().println("Unsupported format: " + format + ". Use text or json.");
            spec.commandLine().getErr().flush();
            return 2;
        }
        return report.hasFailures() ? 1 : 0;
    }

    private DoctorReport runChecks() {
        DoctorReport report = new DoctorReport();
        List<String> analyzers = sorted(analyzerNames.get());
        List<String> backends = sorted(backendNames.get());
        report.add("analyzers", analyzers.isEmpty() ? "FAIL" : "PASS",
                analyzers.isEmpty() ? "No AI analyzer providers discovered" : "Discovered: " + String.join(", ", analyzers));

        String selectedAnalyzer = resolveAnalyzer();
        if (!analyzers.contains(selectedAnalyzer)) {
            report.add("analyzer", "FAIL", "Analyzer not discovered: " + selectedAnalyzer);
        } else {
            checkAnalyzer(report, selectedAnalyzer);
        }

        report.add("llmBackends", backends.isEmpty() ? "FAIL" : "PASS",
                backends.isEmpty() ? "No LLM backend providers discovered" : "Discovered: " + String.join(", ", backends));

        AIBackendOptions backendOptions = backendOptions();
        String selectedBackend = backendNameResolver.apply(backendOptions);
        LLMBackend selected = null;
        if (!backends.contains(selectedBackend)) {
            report.add("llmBackend", "FAIL", "LLM backend not discovered: " + selectedBackend);
        } else {
            selected = checkBackend(report, selectedBackend, backendOptions);
        }

        boolean credentialsOk = checkCredentials(report, selectedBackend);
        checkOnlineAvailability(report, selectedBackend, selected, credentialsOk);
        return report;
    }

    private void checkAnalyzer(DoctorReport report, String selectedAnalyzer) {
        try {
            AIAnalyzer created = analyzerFactory.apply(selectedAnalyzer);
            if (created == null) {
                report.add("analyzer", "FAIL", "Analyzer factory returned null: " + selectedAnalyzer);
                return;
            }
            report.add("analyzer", created.isAvailable() ? "PASS" : "FAIL",
                    "Selected: " + created.getName());
        } catch (Exception e) {
            report.add("analyzer", "FAIL", "Failed to create analyzer " + selectedAnalyzer + ": " + e.getMessage());
        }
    }

    private LLMBackend checkBackend(DoctorReport report, String selectedBackend, AIBackendOptions backendOptions) {
        try {
            LLMBackend created = backendFactory.apply(backendOptions);
            if (created == null) {
                report.add("llmBackend", "FAIL", "Backend factory returned null: " + selectedBackend);
                return null;
            }
            BackendInfo info = created.info();
            String modelText = info == null || info.getModel() == null ? "unknown" : info.getModel();
            report.add("llmBackend", "PASS", "Selected: " + selectedBackend + ", model=" + modelText);
            return created;
        } catch (Exception e) {
            report.add("llmBackend", "FAIL", "Failed to create backend " + selectedBackend + ": " + e.getMessage());
            return null;
        }
    }

    private boolean checkCredentials(DoctorReport report, String selectedBackend) {
        String envName = requiredApiKeyEnv(selectedBackend);
        if (envName == null) {
            if ("noop".equalsIgnoreCase(selectedBackend)) {
                report.add("credentials", "WARN", "noop backend does not call an LLM");
            } else {
                report.add("credentials", "PASS", "No API key required for backend " + selectedBackend);
            }
            return true;
        }
        if (hasText(apiKey) || hasText(envProvider.apply(envName))) {
            report.add("credentials", "PASS", "API key configured via --api-key or " + envName);
            return true;
        }
        report.add("credentials", "FAIL", envName + " or --api-key is required for backend " + selectedBackend);
        return false;
    }

    private void checkOnlineAvailability(DoctorReport report, String selectedBackend, LLMBackend backend, boolean credentialsOk) {
        if (!online) {
            report.add("onlineAvailability", "SKIP", "Use --online to check backend reachability");
            return;
        }
        if ("noop".equalsIgnoreCase(selectedBackend)) {
            report.add("onlineAvailability", "SKIP", "noop backend has no online endpoint");
            return;
        }
        if (backend == null || !credentialsOk) {
            report.add("onlineAvailability", "SKIP", "Skipped because backend configuration is invalid");
            return;
        }
        boolean available = backend.isAvailable();
        report.add("onlineAvailability", available ? "PASS" : "FAIL",
                available ? "Backend is reachable" : "Backend is not reachable");
    }

    private void printText(DoctorReport report) {
        PrintWriter out = spec.commandLine().getOut();
        out.println("# AI Doctor");
        out.println();
        out.println("Checks:");
        for (Map<String, String> check : report.checks) {
            out.println("  - " + check.get("name") + ": " + check.get("status") + " " + check.get("message"));
        }
        out.println();
        out.println("Status: " + report.status());
        out.flush();
    }

    private void printJson(DoctorReport report) throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", report.status());
        payload.put("checks", report.checks);
        out.println(jsonMapper.writeValueAsString(payload));
        out.flush();
    }

    private AIBackendOptions backendOptions() {
        return AIBackendOptions.builder()
                .backend(backend)
                .model(model)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    private String resolveAnalyzer() {
        if (hasText(analyzer)) {
            return analyzer.trim();
        }
        String envAnalyzer = envProvider.apply("CONSILENS_AI_ANALYZER");
        if (hasText(envAnalyzer)) {
            return envAnalyzer.trim();
        }
        return DEFAULT_ANALYZER;
    }

    private String requiredApiKeyEnv(String selectedBackend) {
        if ("openai".equalsIgnoreCase(selectedBackend)) {
            return "OPENAI_API_KEY";
        }
        if ("deepseek".equalsIgnoreCase(selectedBackend)) {
            return "DEEPSEEK_API_KEY";
        }
        return null;
    }

    private List<String> sorted(Set<String> names) {
        return names == null ? List.of() : new ArrayList<>(new TreeSet<>(names));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static LLMBackend resolveBackend(AIBackendOptions options) {
        return new LLMBackendResolver().resolve(options);
    }

    private static String resolveBackendName(AIBackendOptions options) {
        return new LLMBackendResolver().resolveBackendName(options);
    }

    private static class DoctorReport {

        private final List<Map<String, String>> checks = new ArrayList<>();

        private void add(String name, String status, String message) {
            Map<String, String> check = new LinkedHashMap<>();
            check.put("name", name);
            check.put("status", status);
            check.put("message", message);
            checks.add(check);
        }

        private boolean hasFailures() {
            return checks.stream().anyMatch(check -> "FAIL".equals(check.get("status")));
        }

        private String status() {
            if (hasFailures()) {
                return "FAIL";
            }
            return checks.stream().anyMatch(check -> "WARN".equals(check.get("status"))) ? "WARN" : "PASS";
        }
    }
}
