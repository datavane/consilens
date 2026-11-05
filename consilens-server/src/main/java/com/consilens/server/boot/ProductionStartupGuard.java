package com.consilens.server.boot;

import com.consilens.common.io.PathSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ProductionStartupGuard implements InitializingBean {

    private final ConsilensServerProperties properties;
    private final String datasourceUrl;

    public ProductionStartupGuard(ConsilensServerProperties properties,
                                  @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.properties = properties;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getDatabase().isAllowEmbedded() && isEmbeddedDatasource(datasourceUrl)) {
            throw new IllegalStateException("Embedded datasource is not allowed for consilens-server. "
                    + "Configure a persistent spring.datasource.url or set consilens.server.database.allow-embedded=true.");
        }
        if (properties.getSecurity().isEnabled() && isBlank(properties.getSecurity().getApiKey())) {
            throw new IllegalStateException("API key is required for consilens-server. "
                    + "Configure consilens.server.security.api-key or set consilens.server.security.enabled=false.");
        }
        if (!"local-file".equalsIgnoreCase(trim(properties.getArtifact().getStorageType()))) {
            throw new IllegalStateException("Unsupported artifact storage type for consilens-server: "
                    + properties.getArtifact().getStorageType() + ". Only local-file is currently supported.");
        }
        if (isBlank(properties.getArtifact().getLocalBaseDir())) {
            throw new IllegalStateException("Artifact local base directory is required for consilens-server. "
                    + "Configure consilens.server.artifact.local-base-dir.");
        }
        validateLocalArtifactBaseDir(properties.getArtifact().getLocalBaseDir());
        requirePositive(properties.getArtifact().getMaxContentBytes(),
                "consilens.server.artifact.max-content-bytes");
        requirePositive(properties.getNode().getHeartbeatIntervalSeconds(),
                "consilens.server.node.heartbeat-interval-seconds");
        requirePositive(properties.getNode().getExpireSeconds(),
                "consilens.server.node.expire-seconds");
        requirePositive(properties.getScheduler().getCommandPollIntervalMs(),
                "consilens.server.scheduler.command-poll-interval-ms");
        requirePositive(properties.getScheduler().getClaimLeaseSeconds(),
                "consilens.server.scheduler.claim-lease-seconds");
        requirePositive(properties.getScheduler().getExecuteThreads(),
                "consilens.server.scheduler.execute-threads");
        requirePositive(properties.getScheduler().getExecuteQueueCapacity(),
                "consilens.server.scheduler.execute-queue-capacity");
        requirePositive(properties.getScheduler().getRecoveryBatchSize(),
                "consilens.server.scheduler.recovery-batch-size");
        requireNotNegative(properties.getScheduler().getMaxRetryCount(),
                "consilens.server.scheduler.max-retry-count");
        requirePositive(properties.getApi().getMaxRequestBodyBytes(),
                "consilens.server.api.max-request-body-bytes");
    }

    private boolean isEmbeddedDatasource(String url) {
        if (url == null) {
            return true;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.isEmpty()
                || normalized.startsWith("jdbc:h2:")
                || normalized.startsWith("jdbc:hsqldb:mem:")
                || normalized.startsWith("jdbc:derby:memory:");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateLocalArtifactBaseDir(String localBaseDir) {
        try {
            Path path = Path.of(localBaseDir).toAbsolutePath().normalize();
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw new IllegalStateException("Artifact local base path is not a directory: " + path);
            }
            PathSupport.ensureDirectory(path);
            if (!Files.isWritable(path)) {
                throw new IllegalStateException("Artifact local base directory is not writable: " + path);
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Artifact local base directory cannot be initialized: " + localBaseDir,
                    exception);
        }
    }

    private void requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be greater than 0.");
        }
    }

    private void requireNotNegative(int value, String propertyName) {
        if (value < 0) {
            throw new IllegalStateException(propertyName + " must be greater than or equal to 0.");
        }
    }

    private void requirePositive(long value, String propertyName) {
        if (value <= 0L) {
            throw new IllegalStateException(propertyName + " must be greater than 0.");
        }
    }
}
