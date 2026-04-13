package com.consilens.spi;

import lombok.Getter;

@Getter
public class DuplicateProviderException extends RuntimeException {

    private final String registryName;
    private final Object key;

    public DuplicateProviderException(String registryName, Object key, String existingClass, String conflictingClass) {
        super(String.format("[%s] Duplicate provider for key '%s': [%s] and [%s]. Check classpath for duplicate JARs.",
                registryName, key, existingClass, conflictingClass));
        this.registryName = registryName;
        this.key = key;
    }

}
