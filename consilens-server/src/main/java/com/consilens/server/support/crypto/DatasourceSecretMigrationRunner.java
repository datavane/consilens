package com.consilens.server.support.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the one-time plaintext/legacy -> v2 envelope migration at startup.
 * Encryption key missing (MissingKeySecretProtector) or migration failure
 * must never block boot: the runtime reveal() already tolerates plaintext
 * legacy values, so data remains usable either way.
 */
@Component
@Order(1)
public class DatasourceSecretMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceSecretMigrationRunner.class);

    private final DatasourceSecretMigration migration;
    private final SecretProtector protector;

    public DatasourceSecretMigrationRunner(DatasourceSecretMigration migration,
                                           SecretProtector protector) {
        this.migration = migration;
        this.protector = protector;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (protector instanceof AesGcmSecretProtector) {
            try {
                int migrated = migration.migrate();
                if (migrated > 0) {
                    log.info("datasource secret migration completed, rewritten {} row(s)", migrated);
                }
            } catch (Exception e) {
                log.warn("datasource secret migration skipped: {}", e.getMessage());
            }
        } else {
            log.info("datasource encryption key missing, secret migration skipped (plaintext fallback active)");
        }
    }
}
