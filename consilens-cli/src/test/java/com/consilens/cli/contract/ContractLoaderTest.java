package com.consilens.cli.contract;

import com.consilens.cli.command.ContractCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContractLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadOdcsSchemaAndMarkPrimaryKey() throws Exception {
        Path file = write("source.yaml", contract("users",
                "      - name: id\n"
                        + "        logicalType: integer\n"
                        + "        physicalType: bigint\n"
                        + "        required: true\n"
                        + "        primaryKey: true\n"
                        + "      - name: email\n"
                        + "        logicalType: string\n"
                        + "        physicalType: varchar\n"
                        + "        nullable: false\n"
                        + "        unique: true\n"));

        DataContract contract = ContractLoader.load(file);

        assertThat(contract.getId()).isEqualTo("users-contract");
        assertThat(contract.getColumns()).hasSize(2);
        ContractColumn id = contract.columnMap().get("id");
        assertThat(id.effectiveType()).isEqualTo("bigint");
        assertThat(id.getRequired()).isTrue();
        assertThat(id.getPrimaryKey()).isTrue();
        ContractColumn email = contract.columnMap().get("email");
        assertThat(email.getRequired()).isTrue();
        assertThat(email.getUnique()).isTrue();
    }

    @Test
    void shouldLoadDbtModelFromConstraintsAndTests() throws Exception {
        Path file = write("dbt.yml", "version: 2\n"
                + "models:\n"
                + "  - name: users\n"
                + "    columns:\n"
                + "      - name: id\n"
                + "        data_type: bigint\n"
                + "        constraints:\n"
                + "          - type: not_null\n"
                + "          - type: primary_key\n"
                + "      - name: email\n"
                + "        data_type: varchar(255)\n"
                + "        tests:\n"
                + "          - unique\n"
                + "          - not_null\n");

        DataContract contract = ContractLoader.load(file);

        assertThat(contract.getName()).isEqualTo("users");
        assertThat(contract.getColumns()).hasSize(2);
        ContractColumn id = contract.columnMap().get("id");
        assertThat(id.getRequired()).isTrue();
        assertThat(id.getPrimaryKey()).isTrue();
        assertThat(id.effectiveType()).isEqualTo("bigint");
        ContractColumn email = contract.columnMap().get("email");
        assertThat(email.getRequired()).isTrue();
        assertThat(email.getUnique()).isTrue();
        assertThat(email.effectiveType()).isEqualTo("varchar(255)");
    }

    @Test
    void shouldRequireModelNameWhenDbtFileHasMultipleModels() throws Exception {
        Path file = write("dbt-multi.yml", "version: 2\n"
                + "models:\n"
                + "  - name: users\n"
                + "    columns:\n"
                + "      - name: id\n"
                + "        data_type: bigint\n"
                + "  - name: orders\n"
                + "    columns:\n"
                + "      - name: order_id\n"
                + "        data_type: bigint\n");

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ContractLoader.load(file))).hasMessageContaining("multiple models");
        assertThat(ContractLoader.load(file, "orders").columnMap())
                .containsKey("order_id");
    }

    @Test
    void shouldCompareDbtModelAgainstOdcsContract() throws Exception {
        Path dbt = write("dbt-compare.yml", "version: 2\n"
                + "models:\n"
                + "  - name: users\n"
                + "    columns:\n"
                + "      - name: id\n"
                + "        data_type: bigint\n"
                + "        tests: [not_null]\n"
                + "      - name: email\n"
                + "        data_type: varchar(255)\n");
        Path odcs = write("odcs-compare.yaml", contract("users",
                "      - name: id\n"
                        + "        physicalType: bigint\n"
                        + "        required: true\n"
                        + "      - name: email\n"
                        + "        physicalType: varchar\n"
                        + "        required: true\n"));

        ContractDiff diff = ContractDiffer.compare(
                ContractLoader.load(dbt), ContractLoader.load(odcs));

        assertThat(diff.getChanges())
                .extracting(ContractDiff.Change::getKind, ContractDiff.Change::getColumn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.TYPE_CHANGED, "email"),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.REQUIRED_CHANGED, "email"));
    }

    @Test
    void shouldGenerateComparisonConfigFromContracts() throws Exception {
        String properties = "      - name: id\n"
                + "        physicalType: bigint\n"
                + "        primaryKey: true\n"
                + "      - name: email\n"
                + "        physicalType: varchar(255)\n"
                + "      - name: amount\n"
                + "        physicalType: decimal(18,2)\n";
        Path source = write("init-source.yaml", contract("users", properties));
        Path target = write("init-target.yaml", contract("users", properties));

        CommandResult result = execute("init", "-s", source.toString(), "-t", target.toString(),
                "--source-type", "mysql", "--target-type", "postgresql");

        assertThat(result.exitCode).isZero();
        com.fasterxml.jackson.databind.JsonNode config =
                com.consilens.cli.config.CliObjectMappers.yaml().readTree(result.output);
        assertThat(config.at("/source/type").asText()).isEqualTo("mysql");
        assertThat(config.at("/target/type").asText()).isEqualTo("postgresql");
        assertThat(config.at("/source/resource/name").asText()).isEqualTo("users");
        assertThat(config.at("/comparison/keys/source/0").asText()).isEqualTo("id");
        assertThat(config.at("/comparison/fields/source/0").asText()).isEqualTo("email");
        assertThat(config.at("/comparison/fields/source/1").asText()).isEqualTo("amount");
    }

    @Test
    void shouldFailInitWithoutKeys() throws Exception {
        Path source = write("no-key-source.yaml", contract("users",
                "      - name: id\n        physicalType: bigint\n"));
        Path target = write("no-key-target.yaml", contract("users",
                "      - name: id\n        physicalType: bigint\n"));

        CommandResult result = execute("init", "-s", source.toString(), "-t", target.toString(),
                "--source-type", "mysql", "--target-type", "mysql");

        assertThat(result.exitCode).isEqualTo(2);
    }

    @Test
    void shouldCompareContracts() throws Exception {
        Path source = write("source.yaml", contract("users",
                "      - name: id\n"
                        + "        physicalType: bigint\n"
                        + "        required: true\n"
                        + "        primaryKey: true\n"
                        + "      - name: email\n"
                        + "        physicalType: varchar\n"
                        + "        required: true\n"
                        + "        unique: true\n"
                        + "      - name: amount\n"
                        + "        physicalType: decimal(18,2)\n"));
        Path target = write("target.yaml", contract("users",
                "      - name: id\n"
                        + "        physicalType: bigint\n"
                        + "        required: true\n"
                        + "      - name: email\n"
                        + "        physicalType: varchar(255)\n"
                        + "        required: false\n"
                        + "        unique: true\n"
                        + "      - name: created_at\n"
                        + "        physicalType: timestamp\n"));

        ContractDiff diff = ContractDiffer.compare(
                ContractLoader.load(source), ContractLoader.load(target));

        assertThat(diff.isIdentical()).isFalse();
        assertThat(diff.getChanges())
                .extracting(ContractDiff.Change::getKind, ContractDiff.Change::getColumn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.COLUMN_REMOVED, "amount"),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.COLUMN_ADDED, "created_at"),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.TYPE_CHANGED, "email"),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.REQUIRED_CHANGED, "email"),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractDiff.ChangeKind.PRIMARY_KEY_CHANGED, "id"));
    }

    @Test
    void shouldReturnZeroForIdenticalAndOneForChanged() throws Exception {
        Path source = write("same-source.yaml", contract("users",
                "      - name: id\n        physicalType: bigint\n"));
        Path target = write("same-target.yaml", contract("users",
                "      - name: id\n        physicalType: BIGINT\n"));
        Path changed = write("changed.yaml", contract("users",
                "      - name: id\n        physicalType: varchar\n"));

        assertThat(execute("diff", "-s", source.toString(), "-t", target.toString()).exitCode)
                .isZero();
        CommandResult changedResult = execute(
                "diff", "-s", source.toString(), "-t", changed.toString(), "--format", "json");
        assertThat(changedResult.exitCode).isEqualTo(1);
        assertThat(changedResult.output).contains("\"identical\" : false");
        assertThat(execute("diff", "-s", source.toString(), "-t", changed.toString(), "--no-fail")
                .exitCode).isZero();
    }

    private CommandResult execute(String... args) {
        StringWriter writer = new StringWriter();
        CommandLine commandLine = new CommandLine(new ContractCommand());
        commandLine.setOut(new PrintWriter(writer));
        int exitCode = commandLine.execute(args);
        return new CommandResult(exitCode, writer.toString());
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static String contract(String name, String properties) {
        return "apiVersion: v3.0.2\n"
                + "kind: DataContract\n"
                + "id: users-contract\n"
                + "name: " + name + "\n"
                + "schema:\n"
                + "  - name: " + name + "\n"
                + "    properties:\n"
                + properties;
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
