package com.consilens.ai.tool;

import com.consilens.ai.spi.ToolProvider;

import java.util.Arrays;
import java.util.List;

/**
 * Provides all default Consilens AI tools.
 */
public class DefaultToolProvider implements ToolProvider {

    @Override
    public String getName() {
        return "defaults";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public List<Tool> getTools() {
        return Arrays.asList(
                new DiffTool(),
                new AnalyzeTool(),
                new ConfigGenerateTool(),
                new RepairGenerateTool(),
                new SchemaDiscoveryTool()
        );
    }
}
