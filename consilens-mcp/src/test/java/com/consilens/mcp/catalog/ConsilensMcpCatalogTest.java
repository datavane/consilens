package com.consilens.mcp.catalog;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConsilensMcpCatalogTest {

    @Test
    void shouldExposeAtomicToolsWithoutSaveConfig() {
        ConsilensMcpCatalog catalog = new ConsilensMcpCatalog();

        Set<String> names = catalog.toolSpecs().stream()
                .map(McpToolSpec::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "consilens.plan.config",
                "consilens.validate.config",
                "consilens.run.diff",
                "consilens.diagnose.diff",
                "consilens.repair.config",
                "consilens.get.artifact");
        assertThat(names).doesNotContain("consilens.save.config");
    }

    @Test
    void shouldRequireCallerSerialNoForRunDiff() {
        McpToolSpec run = new ConsilensMcpCatalog().findTool("consilens.run.diff").orElseThrow();

        assertThat(run.required()).containsExactly("serialNo", "configArtifactId");
    }

    @Test
    void shouldDescribeValidateAsPostTool() {
        McpToolSpec validate = new ConsilensMcpCatalog().findTool("consilens.validate.config").orElseThrow();

        assertThat(validate.httpMethod()).isEqualTo("POST");
        assertThat(validate.httpPath()).isEqualTo("/v1/validate");
        assertThat(validate.annotations().readOnlyHint()).isFalse();
        assertThat(validate.annotations().idempotentHint()).isTrue();
    }
}
