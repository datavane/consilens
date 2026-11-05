package com.consilens.cli.command;

import com.consilens.cli.contract.ContractDiff;
import com.consilens.cli.contract.ContractDiffer;
import com.consilens.cli.contract.ContractLoader;
import com.consilens.cli.contract.ContractReportWriter;
import com.consilens.cli.contract.DataContract;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Compares two ODCS contracts and reports schema-level changes.
 *
 * <p>Exit codes: 0 identical (or {@code --no-fail}), 1 differences, 2 load or
 * serialization error.
 */
@Command(
        name = "diff",
        description = "Compare two ODCS data contracts",
        mixinStandardHelpOptions = true
)
public class ContractDiffCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-s", "--source"}, required = true, description = "Source contract file")
    private String sourceFile;

    @Option(names = {"-t", "--target"}, required = true, description = "Target contract file")
    private String targetFile;

    @Option(names = {"--source-model"}, description = "dbt model/source-table name in the source file")
    private String sourceModel;

    @Option(names = {"--target-model"}, description = "dbt model/source-table name in the target file")
    private String targetModel;

    @Option(names = {"--format"}, defaultValue = "markdown",
            description = "Report format: markdown | json")
    private String format;

    @Option(names = {"-o", "--output"}, description = "Report output file (default: stdout)")
    private String outputFile;

    @Option(names = {"--no-fail"}, description = "Always exit 0 even when contracts differ")
    private boolean noFail;

    @Override
    public Integer call() {
        try {
            DataContract source = ContractLoader.load(Paths.get(sourceFile), sourceModel);
            DataContract target = ContractLoader.load(Paths.get(targetFile), targetModel);
            ContractDiff diff = ContractDiffer.compare(source, target);
            Path output = outputFile == null || outputFile.isBlank() ? null : Paths.get(outputFile);
            String report = ContractReportWriter.write(diff, format, output);

            if (output != null) {
                spec.commandLine().getOut().println("contract diff: "
                        + (diff.isIdentical() ? "identical" : "changed")
                        + " (" + diff.columnCount() + " changes) -> " + output);
            } else {
                spec.commandLine().getOut().print(report);
                spec.commandLine().getOut().flush();
            }
            return diff.isIdentical() || noFail ? 0 : 1;
        } catch (Exception exception) {
            spec.commandLine().getErr().println("contract diff failed: " + exception.getMessage());
            return 2;
        }
    }
}
