package com.consilens.server.infrastructure.db.repository;

import com.consilens.server.boot.ConsilensServerProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * Applies the configured database time zone so multi-node deployments stay
 * consistent regardless of each JVM's default zone.
 */
@Configuration
public class DbTimeZoneConfig {

    @Bean
    public InitializingBean dbTimeZoneInitializer(ConsilensServerProperties properties) {
        return () -> {
            String zone = properties.getDatabase().getTimeZone();
            DbTimeSupport.configure(zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
        };
    }
}
