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
        // hasNext() 对损坏的 provider 配置也会抛 ServiceConfigurationError；
        // 放进 try 并限制连续失败次数，避免整个注册表被一条坏条目拖垮或死循环。
        int consecutiveErrors = 0;
        while (consecutiveErrors < 10) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                result.add(iterator.next());
                consecutiveErrors = 0;
            } catch (ServiceConfigurationError e) {
                consecutiveErrors++;
                log.error("Failed to load provider for {}: {}", serviceType.getName(), e.getMessage(), e);
            }
        }
        log.debug("Loaded {} providers for {}", result.size(), serviceType.getName());
        return result;
    }
}
