package com.consilens.cli.command;

import com.consilens.ai.spi.AIAnalyzerManager;
import com.consilens.ai.spi.LLMBackendManager;
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
import java.util.function.Supplier;

/**
 * Lists AI providers discovered through SPI.
 */
@Command(
    name = "providers",
    description = "List discovered AI analyzer and LLM backend providers",
    mixinStandardHelpOptions = true
)
public class AiProvidersCommand implements Callable<Integer> {

    private final Supplier<Set<String>> analyzerNames;
    private final Supplier<Set<String>> backendNames;
    private final ObjectMapper jsonMapper;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json (default: text)")
    private String format;

    @Spec
    private CommandSpec spec;

    public AiProvidersCommand() {
        this(
                () -> AIAnalyzerManager.getInstance().supportedNames(),
                () -> LLMBackendManager.getInstance().supportedNames(),
                new ObjectMapper()
        );
    }

    AiProvidersCommand(Supplier<Set<String>> analyzerNames, Supplier<Set<String>> backendNames) {
        this(analyzerNames, backendNames, new ObjectMapper());
    }

    AiProvidersCommand(Supplier<Set<String>> analyzerNames,
                       Supplier<Set<String>> backendNames,
                       ObjectMapper jsonMapper) {
        this.analyzerNames = analyzerNames;
        this.backendNames = backendNames;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Integer call() throws Exception {
        List<String> analyzers = sorted(analyzerNames.get());
        List<String> backends = sorted(backendNames.get());
        if ("json".equalsIgnoreCase(format)) {
            Map<String, List<String>> payload = new LinkedHashMap<>();
            payload.put("analyzers", analyzers);
            payload.put("llmBackends", backends);
            PrintWriter out = spec.commandLine().getOut();
            out.println(jsonMapper.writeValueAsString(payload));
            out.flush();
            return 0;
        }
        if (!"text".equalsIgnoreCase(format)) {
            spec.commandLine().getErr().println("Unsupported format: " + format + ". Use text or json.");
            spec.commandLine().getErr().flush();
            return 2;
        }

        PrintWriter out = spec.commandLine().getOut();
        out.println("# AI Providers");
        out.println();
        printGroup(out, "Analyzers", analyzers);
        out.println();
        printGroup(out, "LLM Backends", backends);
        out.flush();
        return 0;
    }

    private void printGroup(PrintWriter out, String title, List<String> names) {
        out.println(title + ":");
        if (names.isEmpty()) {
            out.println("  - none");
            return;
        }
        names.forEach(name -> out.println("  - " + name));
    }

    private List<String> sorted(Set<String> names) {
        return names == null ? List.of() : new ArrayList<>(new TreeSet<>(names));
    }
}
