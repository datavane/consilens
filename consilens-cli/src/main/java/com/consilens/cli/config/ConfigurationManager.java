package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.common.io.PathSupport;
import com.consilens.core.exception.ExceptionHandler;
import com.consilens.core.util.LogUtils;
import com.consilens.core.validation.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.Getter;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Unified configuration manager providing load, cache, and validate support.
 */
public class ConfigurationManager {

    private final Map<String, ObjectMapper> mappers;
    private final Map<String, CachedConfiguration> configCache;
    private final ReentrantReadWriteLock cacheLock;
    private final ConfigNormalizer configNormalizer;
    private final EnvironmentPlaceholderResolver environmentPlaceholderResolver;

    public ConfigurationManager() {
        this(System.getenv());
    }

    public ConfigurationManager(Map<String, String> environment) {
        this.mappers = createMappers();
        this.configCache = new ConcurrentHashMap<>();
        this.cacheLock = new ReentrantReadWriteLock();
        this.configNormalizer = new ConfigNormalizer();
        this.environmentPlaceholderResolver = new EnvironmentPlaceholderResolver(environment);
    }

    /**
     * Load configuration file with caching enabled.
     *
     * @param configPath configuration file path
     * @return configuration object
     * @throws ConfigurationException if loading fails
     */
    public CliConfiguration loadConfiguration(String configPath) throws ConfigurationException {
        return loadConfiguration(configPath, true);
    }

    /**
     * Load configuration file.
     *
     * @param configPath configuration file path
     * @param useCache   whether to use the cache
     * @return configuration object
     * @throws ConfigurationException if loading fails
     */
    public CliConfiguration loadConfiguration(String configPath, boolean useCache) throws ConfigurationException {
        Objects.requireNonNull(configPath, "Configuration path cannot be null");

        Path path = Paths.get(configPath).toAbsolutePath().normalize();

        // Check cache
        if (useCache) {
            CachedConfiguration cached = getCachedConfiguration(path.toString());
            if (cached != null && !cached.isExpired(path)) {
                LogUtils.logDebug("Using cached configuration", "path=" + configPath);
                return cached.getConfig();
            }
        }

        // Validate file
        validateFile(path);

        // Load configuration
        CliConfiguration config;
        try {
            config = ExceptionHandler.execute(() -> {
                String format = detectFormat(path);
                ObjectMapper mapper = mappers.get(format);
                if (mapper == null) {
                    throw new ConfigurationException("Unsupported configuration format: " + format);
                }

                LogUtils.logConfigurationLoad(format.toUpperCase(), path.toString());
                JsonNode rawConfig = mapper.readTree(path.toFile());
                JsonNode resolvedConfig = environmentPlaceholderResolver.resolve(rawConfig);
                return mapper.treeToValue(resolvedConfig, CliConfiguration.class);
            }, "Load configuration from " + path);
        } catch (RuntimeException e) {
            throw unwrapConfigurationException(e);
        }

        // Normalize + validate
        try {
            config = configNormalizer.normalize(config);
        } catch (ValidationException e) {
            throw new ConfigurationException(e.getMessage(), e);
        }

        // Cache configuration
        if (useCache) {
            cacheConfiguration(path.toString(), config);
        }

        LogUtils.logConfigurationLoadSuccess("CLI", path.toString());
        return config;
    }

    /**
     * Load configuration from an input stream.
     *
     * @param inputStream input stream
     * @param format      configuration format
     * @return configuration object
     * @throws ConfigurationException if loading fails
     */
    public CliConfiguration loadConfiguration(InputStream inputStream, String format) throws ConfigurationException {
        Objects.requireNonNull(inputStream, "Input stream cannot be null");
        Objects.requireNonNull(format, "Format cannot be null");

        String normalizedFormat = format.toLowerCase();
        ObjectMapper mapper = mappers.get(normalizedFormat);
        if (mapper == null) {
            throw new ConfigurationException("Unsupported configuration format: " + format);
        }

        try {
            return ExceptionHandler.execute(() -> {
                LogUtils.logDebug("Loading configuration from input stream", "format=" + format);
                JsonNode rawConfig = mapper.readTree(inputStream);
                JsonNode resolvedConfig = environmentPlaceholderResolver.resolve(rawConfig);
                CliConfiguration config = mapper.treeToValue(resolvedConfig, CliConfiguration.class);
                try {
                    config = configNormalizer.normalize(config);
                } catch (ValidationException e) {
                    throw new ConfigurationException(e.getMessage(), e);
                }
                return config;
            }, "Load configuration from input stream");
        } catch (RuntimeException e) {
            throw unwrapConfigurationException(e);
        }
    }

    /**
     * Save configuration to a file.
     *
     * @param config     configuration object
     * @param outputPath output file path
     * @param format     output format
     * @throws ConfigurationException if saving fails
     */
    public void saveConfiguration(CliConfiguration config, String outputPath, String format) throws ConfigurationException {
        Objects.requireNonNull(config, "Configuration cannot be null");
        Objects.requireNonNull(outputPath, "Output path cannot be null");
        Objects.requireNonNull(format, "Format cannot be null");

        String normalizedFormat = format.toLowerCase();
        ObjectMapper mapper = mappers.get(normalizedFormat);
        if (mapper == null) {
            throw new ConfigurationException("Unsupported output format: " + format);
        }

        ExceptionHandler.execute(() -> {
            Path path = Paths.get(outputPath).toAbsolutePath().normalize();

            // Create parent directories
            PathSupport.ensureParent(path);

            // Configure output on a copy so the shared mapper stays immutable
            // and pretty-printing does not leak into other callers.
            ObjectMapper outputMapper = mapper.copy();
            outputMapper.configure(SerializationFeature.INDENT_OUTPUT, true);

            LogUtils.logDebug("Saving configuration", "path=" + outputPath, "format=" + format);
            outputMapper.writeValue(path.toFile(), config);

            // Evict cache
            invalidateCache(path.toString());

            LogUtils.logOperationSuccess("Configuration saved with path: " + path.toString());
            return null; // Void operation
        }, "Save configuration to " + outputPath);
    }

    /**
     * Validate a configuration file.
     *
     * @param configPath configuration file path
     * @return validation result
     */
    public ValidationResult validateConfigurationFile(String configPath) {
        try {
            CliConfiguration config = loadConfiguration(configPath, false); // skip cache for validation
            return ValidationResult.success("Configuration file is valid");
        } catch (ConfigurationException e) {
            return ValidationResult.error(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.error("Unexpected error during validation: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * Invalidate the cache entry for the given configuration path.
     *
     * @param configPath configuration file path
     */
    public void invalidateCache(String configPath) {
        cacheLock.writeLock().lock();
        try {
            configCache.remove(configPath);
            LogUtils.logDebug("Configuration cache invalidated", "path=" + configPath);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Create ObjectMapper instances for YAML and JSON.
     */
    private Map<String, ObjectMapper> createMappers() {
        Map<String, ObjectMapper> mappers = new HashMap<>();

        // Shared, immutable mapper instances: Java time + NON_NULL inclusion.
        ObjectMapper yamlMapper = CliObjectMappers.yamlWithJavaTimeNonNull();
        mappers.put("yaml", yamlMapper);
        mappers.put("yml", yamlMapper);

        ObjectMapper jsonMapper = CliObjectMappers.jsonWithJavaTimeNonNull();
        mappers.put("json", jsonMapper);

        return mappers;
    }

    /**
     * Validate that the file exists, is a regular file, and is readable.
     */
    private void validateFile(Path path) throws ConfigurationException {
        if (!Files.exists(path)) {
            throw new ConfigurationException("Configuration file not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ConfigurationException("Configuration path is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new ConfigurationException("Configuration file is not readable: " + path);
        }
    }

    /**
     * Detect configuration file format from file extension.
     */
    public String detectFormat(String filePath) throws ConfigurationException {
        return detectFormat(Paths.get(filePath));
    }

    /**
     * Detect configuration file format from path.
     */
    private String detectFormat(Path path) throws ConfigurationException {
        String fileName = path.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return "yaml";
        } else if (fileName.endsWith(".json")) {
            return "json";
        } else {
            throw new ConfigurationException("Unsupported configuration file format: " + fileName +
                    ". Supported formats: .yaml, .yml, .json");
        }
    }


    /**
     * Store configuration in cache.
     */
    private void cacheConfiguration(String path, CliConfiguration config) {
        cacheLock.writeLock().lock();
        try {
            configCache.put(path, new CachedConfiguration(config));
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Retrieve a cached configuration entry.
     */
    private CachedConfiguration getCachedConfiguration(String path) {
        cacheLock.readLock().lock();
        try {
            return configCache.get(path);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    private ConfigurationException unwrapConfigurationException(RuntimeException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof ConfigurationException) {
            return (ConfigurationException) cause;
        }
        String detail = deepestMessage(cause);
        String message = exception.getMessage();
        if (detail == null || message == null || message.contains(detail)) {
            return new ConfigurationException(message, exception);
        }
        return new ConfigurationException(message + ": " + detail, exception);
    }

    /**
     * Parse failures are wrapped as "Load configuration from <path>"; the useful
     * Jackson/SnakeYAML message (including line/column) lives deeper in the cause
     * chain.
     */
    private static String deepestMessage(Throwable cause) {
        String best = null;
        Throwable current = cause;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current.getMessage();
            }
            current = current.getCause();
        }
        return best;
    }

    /**
     * Cached configuration entry.
     */
    private static class CachedConfiguration {
        @Getter
        private final CliConfiguration config;
        private final Instant createdAt;
        private final long maxAgeMillis;

        public CachedConfiguration(CliConfiguration config) {
            this.config = config;
            this.createdAt = Instant.now();
            this.maxAgeMillis = 300000; // 5 minutes
        }

        public boolean isExpired(Path path) {
            try {
                return Instant.now().isAfter(createdAt.plusMillis(maxAgeMillis)) ||
                       Files.getLastModifiedTime(path).toInstant().isAfter(createdAt);
            } catch (IOException e) {
                return true; // Treat as expired if file time cannot be checked
            }
        }

    }

    /**
     * Validation result.
     */
    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult success(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

    }
}
