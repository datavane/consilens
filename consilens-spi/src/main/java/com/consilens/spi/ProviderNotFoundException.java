package com.consilens.spi;

import lombok.Getter;

import java.util.Set;

@Getter
public class ProviderNotFoundException extends RuntimeException {

    private final String registryName;
    private final Object key;

    public ProviderNotFoundException(String registryName, Object key, Set<?> availableKeys) {
        super(String.format("[%s] No provider found for key '%s'. Available keys: %s. Ensure the corresponding plugin JAR is on the classpath.",
                registryName, key, availableKeys));
        this.registryName = registryName;
        this.key = key;
    }

}
