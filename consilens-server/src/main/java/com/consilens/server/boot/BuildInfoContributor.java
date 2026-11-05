package com.consilens.server.boot;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Exposes build metadata at {@code /actuator/info}. The generated file lives at
 * the classpath root so it remains visible inside the executable jar, where
 * Boot keeps {@code META-INF/} outside the application classpath.
 */
@Component
public class BuildInfoContributor implements InfoContributor {

    private static final String RESOURCE = "build-info.properties";

    @Override
    public void contribute(Info.Builder builder) {
        Properties properties = load();
        if (properties.isEmpty()) {
            return;
        }
        Map<String, Object> build = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("build.")) {
                build.put(name.substring("build.".length()), properties.getProperty(name));
            }
        }
        if (!build.isEmpty()) {
            builder.withDetail("build", build);
        }
    }

    private Properties load() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Info is diagnostic only; a missing/unreadable file must not fail startup.
        }
        return properties;
    }
}
