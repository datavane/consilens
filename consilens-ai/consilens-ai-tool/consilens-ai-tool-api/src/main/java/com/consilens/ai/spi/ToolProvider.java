package com.consilens.ai.spi;

import com.consilens.ai.tool.Tool;

import java.util.List;

/**
 * SPI interface for providing tools to the AI agent.
 */
public interface ToolProvider {

    /**
     * Returns the list of tools provided by this provider.
     */
    List<Tool> getTools();

    /**
     * Returns the unique name of this provider.
     */
    String getName();

    /**
     * Returns the loading order for this provider (lower = loaded first).
     */
    default int getOrder() {
        return 100;
    }
}
