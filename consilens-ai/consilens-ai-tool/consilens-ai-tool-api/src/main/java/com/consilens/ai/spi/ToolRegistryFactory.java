package com.consilens.ai.spi;

import com.consilens.ai.tool.Tool;
import com.consilens.ai.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Factory that creates a {@link ToolRegistry} populated with all SPI-registered {@link ToolProvider}s.
 */
@Slf4j
public class ToolRegistryFactory {

    private ToolRegistryFactory() {
    }

    /**
     * Loads all {@link ToolProvider}s from the classpath via {@link ServiceLoader} and registers their tools.
     *
     * @return a fully populated {@link ToolRegistry}
     */
    public static ToolRegistry loadDefault() {
        ToolRegistry registry = new ToolRegistry();
        ServiceLoader<ToolProvider> loader = ServiceLoader.load(ToolProvider.class);
        loader.stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(ToolProvider::getOrder))
                .forEach(provider -> {
                    log.debug("Loading tools from provider: {}", provider.getName());
                    provider.getTools().forEach(registry::register);
                });
        return registry;
    }
}
