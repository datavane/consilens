package com.consilens.server.boot;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ProductionStartupGuardTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldRejectEmbeddedDatasourceByDefault() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, "jdbc:h2:mem:consilens");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldRejectH2FileDatasourceByDefault() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, "jdbc:h2:file:/tmp/consilens");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldAllowEmbeddedDatasourceWhenExplicitlyEnabled() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getDatabase().setAllowEmbedded(true);
        properties.getSecurity().setEnabled(false);
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, "jdbc:h2:mem:consilens");

        guard.afterPropertiesSet();
    }

    @Test
    void shouldAllowPersistentMysqlDatasourceByDefault() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ProductionStartupGuard guard = new ProductionStartupGuard(properties,
                "jdbc:mysql://mysql.example.com:3306/consilens");

        guard.afterPropertiesSet();
    }

    @Test
    void shouldRejectMissingDatasourceByDefault() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ProductionStartupGuard guard = new ProductionStartupGuard(properties, " ");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldRejectMissingApiKeyWhenSecurityEnabled() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ProductionStartupGuard guard = new ProductionStartupGuard(properties,
                "jdbc:mysql://mysql.example.com:3306/consilens");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldAllowMissingApiKeyWhenSecurityDisabled() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setEnabled(false);
        ProductionStartupGuard guard = new ProductionStartupGuard(properties,
                "jdbc:mysql://mysql.example.com:3306/consilens");

        guard.afterPropertiesSet();
    }

    @Test
    void shouldRejectUnsupportedArtifactStorageType() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        properties.getArtifact().setStorageType("s3");
        ProductionStartupGuard guard = new ProductionStartupGuard(properties,
                "jdbc:mysql://mysql.example.com:3306/consilens");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldRejectBlankLocalArtifactBaseDir() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        properties.getArtifact().setLocalBaseDir(" ");
        ProductionStartupGuard guard = new ProductionStartupGuard(properties,
                "jdbc:mysql://mysql.example.com:3306/consilens");

        Assertions.assertThrows(IllegalStateException.class,
                guard::afterPropertiesSet);
    }

    @Test
    void shouldCreateMissingLocalArtifactBaseDirOnStartup() throws Exception {
        ConsilensServerProperties properties = validProperties();
        Path artifactDir = tempDir.resolve("artifacts");
        properties.getArtifact().setLocalBaseDir(artifactDir.toString());

        guard(properties).afterPropertiesSet();

        Assertions.assertTrue(Files.isDirectory(artifactDir));
    }

    @Test
    void shouldRejectLocalArtifactBaseDirWhenPathIsFile() throws Exception {
        ConsilensServerProperties properties = validProperties();
        Path artifactFile = tempDir.resolve("artifact-file");
        Files.writeString(artifactFile, "not a directory");
        properties.getArtifact().setLocalBaseDir(artifactFile.toString());

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectInvalidArtifactMaxContentBytes() {
        ConsilensServerProperties properties = validProperties();
        properties.getArtifact().setMaxContentBytes(0L);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectInvalidApiMaxRequestBodyBytes() {
        ConsilensServerProperties properties = validProperties();
        properties.getApi().setMaxRequestBodyBytes(0L);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectInvalidNodeTimingProperties() {
        ConsilensServerProperties properties = validProperties();
        properties.getNode().setHeartbeatIntervalSeconds(0);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectInvalidSchedulerTimingProperties() {
        ConsilensServerProperties properties = validProperties();
        properties.getScheduler().setCommandPollIntervalMs(0);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectInvalidSchedulerCapacityProperties() {
        ConsilensServerProperties properties = validProperties();
        properties.getScheduler().setExecuteQueueCapacity(0);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldRejectNegativeMaxRetryCount() {
        ConsilensServerProperties properties = validProperties();
        properties.getScheduler().setMaxRetryCount(-1);

        Assertions.assertThrows(IllegalStateException.class,
                () -> guard(properties).afterPropertiesSet());
    }

    @Test
    void shouldAllowZeroMaxRetryCount() throws Exception {
        ConsilensServerProperties properties = validProperties();
        properties.getScheduler().setMaxRetryCount(0);

        guard(properties).afterPropertiesSet();
    }

    private ConsilensServerProperties validProperties() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        return properties;
    }

    private ProductionStartupGuard guard(ConsilensServerProperties properties) {
        return new ProductionStartupGuard(properties, "jdbc:mysql://mysql.example.com:3306/consilens");
    }
}
