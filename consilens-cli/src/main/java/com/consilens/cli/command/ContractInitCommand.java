package com.consilens.cli.command;

import com.consilens.cli.config.CliObjectMappers;
import com.consilens.cli.contract.ContractColumn;
import com.consilens.cli.contract.ContractLoader;
import com.consilens.cli.contract.DataContract;
import com.consilens.common.io.PathSupport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Generates a comparison configuration skeleton from two data contracts.
 *
 * <p>Only schema-derived fields are generated (resource names, keys, compared
 * columns). Connection details must be filled in by the user; the command never
 * guesses URLs or credentials.
 *
 * <p>Exit codes: 0 generated, 2 input/selection error.
 */
@Command(
        name = "init",
        description = "Generate a comparison config skeleton from two contracts",
        mixinStandardHelpOptions = true
)
public class ContractInitCommand implements Callable<Integer> {

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

    @Option(names = {"--source-type"}, required = true,
            description = "Connector type for the source (for example mysql, postgresql)")
    private String sourceType;

    @Option(names = {"--target-type"}, required = true,
            description = "Connector type for the target (for example mysql, postgresql)")
    private String targetType;

    @Option(names = {"--keys"}, split = ",",
            description = "Comma-separated keys; defaults to contract primary keys")
    private List<String> keys;

    @Option(names = {"-o", "--output"}, description = "Output config file (default: stdout)")
    private String outputFile;

    @Override
    public Integer call() {
        try {
            DataContract source = ContractLoader.load(Paths.get(sourceFile), sourceModel);
            DataContract target = ContractLoader.load(Paths.get(targetFile), targetModel);
            List<String> selectedKeys = selectKeys(source, target);
            List<String> fields = selectFields(source, target, selectedKeys);

            Map<String, Object> config = buildConfig(source, target, selectedKeys, fields);
            String yaml = CliObjectMappers.yaml().writeValueAsString(config);
            if (outputFile == null || outputFile.isBlank()) {
                spec.commandLine().getOut().print(yaml);
                spec.commandLine().getOut().flush();
            } else {
                Path output = Paths.get(outputFile);
                PathSupport.ensureParent(output);
                Files.writeString(output, yaml);
                spec.commandLine().getOut().println("contract init: wrote " + output
                        + " (keys=" + selectedKeys.size() + ", fields=" + fields.size() + ")");
            }
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println("contract init failed: " + exception.getMessage());
            return 2;
        }
    }

    private List<String> selectKeys(DataContract source, DataContract target) {
        List<String> selected;
        if (keys != null && !keys.isEmpty()) {
            selected = keys.stream().map(String::trim).filter(key -> !key.isEmpty())
                    .collect(Collectors.toList());
        } else {
            selected = source.columnMap().values().stream()
                    .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
                    .map(ContractColumn::getName)
                    .collect(Collectors.toList());
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("no primary key found; pass --keys id[,..]");
        }
        for (String key : selected) {
            if (!source.columnMap().containsKey(key)) {
                throw new IllegalArgumentException("key '" + key + "' not found in source contract");
            }
            if (!target.columnMap().containsKey(key)) {
                throw new IllegalArgumentException("key '" + key + "' not found in target contract");
            }
        }
        return selected;
    }

    private List<String> selectFields(DataContract source, DataContract target, List<String> keys) {
        Set<String> keySet = new LinkedHashSet<>(keys);
        Set<String> targetColumns = target.columnMap().keySet();
        return source.columnMap().keySet().stream()
                .filter(column -> !keySet.contains(column))
                .filter(targetColumns::contains)
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildConfig(DataContract source, DataContract target,
                                            List<String> keys, List<String> fields) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source", side(sourceType, source.getName()));
        config.put("target", side(targetType, target.getName()));

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("keys", pair(keys, keys));
        comparison.put("fields", pair(new ArrayList<>(fields), new ArrayList<>(fields)));
        config.put("comparison", comparison);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> console = new LinkedHashMap<>();
        console.put("format", "console");
        console.put("type", "result");
        result.put("sinks", List.of(console));
        config.put("result", result);
        return config;
    }

    private Map<String, Object> side(String type, String name) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("type", type);
        side.put("name", name);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", "table");
        resource.put("name", name);
        side.put("resource", resource);
        return side;
    }

    private Map<String, Object> pair(List<String> source, List<String> target) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("source", source);
        pair.put("target", target);
        return pair;
    }
}
