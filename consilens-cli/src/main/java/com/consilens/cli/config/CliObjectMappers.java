package com.consilens.cli.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared, pre-configured {@link ObjectMapper} instances for the CLI module.
 *
 * <p>Every mapper returned here is a process-wide singleton and is fully
 * configured during class initialization. {@link ObjectMapper} is thread-safe
 * for read/write operations once configured, so the instances may be shared
 * across managers, commands and threads. Callers must not reconfigure the
 * returned instances: make a {@link ObjectMapper#copy()} first when a
 * different configuration is required for one operation (for example pretty
 * printing).
 */
public final class CliObjectMappers {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper JSON_WITH_JAVA_TIME =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final ObjectMapper JSON_WITH_JAVA_TIME_NON_NULL = jsonMapper(true, true, false);

    private static final ObjectMapper JSON_WITH_JAVA_TIME_INDENTED = jsonMapper(true, true, true);

    private static final ObjectMapper YAML_WITH_JAVA_TIME_NON_NULL = yamlMapper();

    private static final ObjectMapper JSON_WITH_DISCOVERED_MODULES =
            new ObjectMapper().findAndRegisterModules();

    private CliObjectMappers() {
        // Utility class, do not instantiate.
    }

    /**
     * Plain YAML mapper without extra modules or inclusion rules.
     */
    public static ObjectMapper yaml() {
        return YAML;
    }

    /**
     * Plain JSON mapper without extra modules or inclusion rules.
     */
    public static ObjectMapper json() {
        return JSON;
    }

    /**
     * JSON mapper with the Java 8 date/time module registered.
     */
    public static ObjectMapper jsonWithJavaTime() {
        return JSON_WITH_JAVA_TIME;
    }

    /**
     * JSON mapper with Java time support and {@code NON_NULL} serialization
     * inclusion, matching the configuration loader's JSON mapper.
     */
    public static ObjectMapper jsonWithJavaTimeNonNull() {
        return JSON_WITH_JAVA_TIME_NON_NULL;
    }

    /**
     * JSON mapper with Java time support, {@code NON_NULL} inclusion and
     * indented output, used by the file-backed AI runtime stores.
     */
    public static ObjectMapper jsonWithJavaTimeIndented() {
        return JSON_WITH_JAVA_TIME_INDENTED;
    }

    /**
     * YAML mapper with Java time support and {@code NON_NULL} serialization
     * inclusion, matching the configuration loader's YAML mapper.
     */
    public static ObjectMapper yamlWithJavaTimeNonNull() {
        return YAML_WITH_JAVA_TIME_NON_NULL;
    }

    /**
     * JSON mapper that registers every module discoverable through
     * {@link ObjectMapper#findAndRegisterModules()}.
     */
    public static ObjectMapper jsonWithDiscoveredModules() {
        return JSON_WITH_DISCOVERED_MODULES;
    }

    private static ObjectMapper jsonMapper(boolean javaTime, boolean nonNull, boolean indented) {
        ObjectMapper mapper = new ObjectMapper();
        if (javaTime) {
            mapper.registerModule(new JavaTimeModule());
        }
        if (nonNull) {
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }
        if (indented) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        return mapper;
    }

    private static ObjectMapper yamlMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.registerModule(new JavaTimeModule());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
