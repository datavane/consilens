package com.consilens.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class KeyedRegistry<K, P> {

    private static final Logger log = LoggerFactory.getLogger(KeyedRegistry.class);

    private final Map<K, P> providers;
    private final String registryName;

    private KeyedRegistry(Map<K, P> providers, String registryName) {
        this.providers = Collections.unmodifiableMap(providers);
        this.registryName = registryName;
    }

    public static <K, P> KeyedRegistry<K, P> load(Class<P> providerType, Function<P, K> keyExtractor) {
        return load(providerType, keyExtractor, providerType.getSimpleName());
    }

    public static <K, P> KeyedRegistry<K, P> load(Class<P> providerType,
                                                  Function<P, K> keyExtractor,
                                                  String registryName) {
        return from(ServiceLoaderUtils.loadAll(providerType), keyExtractor, registryName);
    }

    @SafeVarargs
    public static <K, P> KeyedRegistry<K, P> of(Function<P, K> keyExtractor, P... providers) {
        return from(Arrays.asList(providers), keyExtractor, "test");
    }

    public static <K, P> KeyedRegistry<K, P> from(Iterable<P> providers,
                                                  Function<P, K> keyExtractor,
                                                  String registryName) {
        Map<K, P> map = new LinkedHashMap<>();
        for (P provider : providers) {
            K key = keyExtractor.apply(provider);
            Objects.requireNonNull(key, () -> "Provider " + provider.getClass().getName()
                    + " returned null key in registry [" + registryName + "]");
            P existing = map.put(key, provider);
            if (existing != null) {
                throw new DuplicateProviderException(registryName, key,
                        existing.getClass().getName(), provider.getClass().getName());
            }
            log.info("[{}] Registered: {} -> {}", registryName, key, provider.getClass().getName());
        }
        log.info("[{}] Initialized with {} providers: {}", registryName, map.size(), map.keySet());
        return new KeyedRegistry<>(map, registryName);
    }

    public P get(K key) {
        P provider = providers.get(key);
        if (provider == null) {
            throw new ProviderNotFoundException(registryName, key, providers.keySet());
        }
        return provider;
    }

    public P find(K key) {
        return providers.get(key);
    }

    public boolean supports(K key) {
        return providers.containsKey(key);
    }

    public Set<K> keys() {
        return providers.keySet();
    }

    public Set<K> getSupportedKeys() {
        return keys();
    }

    public Collection<P> providers() {
        return providers.values();
    }

    public Map<K, P> asMap() {
        return providers;
    }

    public int size() {
        return providers.size();
    }
}
