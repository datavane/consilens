package com.consilens.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public final class ServiceLoaderUtils {

    private static final Logger log = LoggerFactory.getLogger(ServiceLoaderUtils.class);

    private ServiceLoaderUtils() {
    }

    public static <S> List<S> loadAll(Class<S> serviceType) {
        return loadAll(serviceType, Thread.currentThread().getContextClassLoader());
    }

    public static <S> List<S> loadAll(Class<S> serviceType, ClassLoader classLoader) {
        List<S> result = new ArrayList<>();
        ServiceLoader<S> loader = ServiceLoader.load(serviceType, classLoader);
        Iterator<S> iterator = loader.iterator();
        while (iterator.hasNext()) {
            try {
                result.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                log.error("Failed to load provider for {}: {}", serviceType.getName(), e.getMessage(), e);
            }
        }
        log.debug("Loaded {} providers for {}", result.size(), serviceType.getName());
        return result;
    }

    public static <S> List<S> loadAllStrict(Class<S> serviceType) {
        return loadAllStrict(serviceType, Thread.currentThread().getContextClassLoader());
    }

    public static <S> List<S> loadAllStrict(Class<S> serviceType, ClassLoader classLoader) {
        List<S> result = new ArrayList<>();
        for (S provider : ServiceLoader.load(serviceType, classLoader)) {
            result.add(provider);
        }
        return result;
    }
}
