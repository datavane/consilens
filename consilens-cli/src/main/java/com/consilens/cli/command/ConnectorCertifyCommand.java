package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.connector.ConnectorCertificationReportWriter;
import com.consilens.cli.connector.SideCertification;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.spi.ConnectorProvider;
import com.consilens.connector.testkit.CertificationReport;
import com.consilens.connector.testkit.ConnectorCertification;
import com.consilens.core.compare.registry.DefaultConnectorRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Runs the connector certification checks against the configured source and/or
 * target. Exit codes: 0 all checks pass, 1 any check fails, 2 input error.
 */
@Command(
        name = "certify",
        description = "Run connector contract checks against a comparison config",
        mixinStandardHelpOptions = true
)
public class ConnectorCertifyCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--side"}, defaultValue = "both",
            description = "Which side to certify: source | target | both")
    private String side;

    @Option(names = {"--max-rows"}, defaultValue = "100",
            description = "Maximum rows read per scan (default 100)")
    private int maxRows;

    @Option(names = {"--format"}, defaultValue = "markdown",
            description = "Report format: markdown | json")
    private String format;

    @Option(names = {"-o", "--output"}, description = "Report output file (default: stdout)")
    private String outputFile;

    @Override
    public Integer call() {
        try {
            String selected = side == null ? "both" : side.trim().toLowerCase(Locale.ROOT);
            if (!"source".equals(selected) && !"target".equals(selected) && !"both".equals(selected)) {
                throw new IllegalArgumentException("--side must be source, target or both");
            }
            if (maxRows <= 0) {
                throw new IllegalArgumentException("--max-rows must be positive");
            }
            CliConfiguration config = new ConfigurationManager().loadConfiguration(configFile);
            CompareRequest request = new CompareRequestFactory().create(config);
            DefaultConnectorRegistry registry = new DefaultConnectorRegistry();

            List<SideCertification> results = new ArrayList<>();
            if (!"target".equals(selected)) {
                results.add(new SideCertification("source", certify(registry,
                        request.getSource(), request.getSourceKeySpec())));
            }
            if (!"source".equals(selected)) {
                results.add(new SideCertification("target", certify(registry,
                        request.getTarget(), request.getTargetKeySpec())));
            }

            Path output = outputFile == null || outputFile.isBlank() ? null : Paths.get(outputFile);
            String report = ConnectorCertificationReportWriter.write(results, format, output);
            boolean passed = ConnectorCertificationReportWriter.allPassed(results);
            if (output != null) {
                spec.commandLine().getOut().println("connector certify: " + (passed ? "PASS" : "FAIL")
                        + " (" + results.size() + " sides) -> " + output);
            } else {
                spec.commandLine().getOut().print(report);
                spec.commandLine().getOut().flush();
            }
            return passed ? 0 : 1;
        } catch (Exception exception) {
            spec.commandLine().getErr().println("connector certify failed: "
                    + exception.getMessage());
            return 2;
        }
    }

    private CertificationReport certify(DefaultConnectorRegistry registry, ConnectorConfig config,
                                        KeySpec keySpec) {
        Optional<ConnectorProvider> provider = registry.findProvider(config.getType());
        if (provider.isEmpty()) {
            CertificationReport report = new CertificationReport(config.getType(),
                    config.getResource() == null ? null : config.getResource().getName());
            report.add("provider", false, "no provider registered for type " + config.getType());
            return report;
        }
        return ConnectorCertification.verify(provider.get(), config, keySpec, maxRows);
    }
}
