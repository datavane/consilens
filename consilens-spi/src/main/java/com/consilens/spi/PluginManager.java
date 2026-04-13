package com.consilens.spi;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class PluginManager<K, P, T> {

    private final KeyedRegistry<K, P> registry;
    private final CachingFactory<K, T> factory;
    private final BiFunction<P, Map<String, ?>, T> configuredCreator;

    private PluginManager(KeyedRegistry<K, P> registry,
                          CachingFactory<K, T> factory,
                          BiFunction<P, Map<String, ?>, T> configuredCreator) {
        this.registry = registry;
        this.factory = factory;
        this.configuredCreator = configuredCreator;
    }

    public static <K, P, T> PluginManager<K, P, T> load(Class<P> providerType,
                                                        Function<P, K> keyExtractor,
                                                        Function<P, T> singletonCreator,
                                                        BiFunction<P, Map<String, ?>, T> configuredCreator,
                                                        String managerName) {
        KeyedRegistry<K, P> registry = KeyedRegistry.load(providerType, keyExtractor, managerName);
        return from(registry, singletonCreator, configuredCreator);
    }

    public static <K, P, T> PluginManager<K, P, T> from(KeyedRegistry<K, P> registry,
                                                        Function<P, T> singletonCreator,
                                                        BiFunction<P, Map<String, ?>, T> configuredCreator) {
        CachingFactory<K, T> factory = new CachingFactory<>(key -> singletonCreator.apply(registry.get(key)));
        return new PluginManager<>(registry, factory, configuredCreator);
    }

    @SafeVarargs
    public static <K, P, T> PluginManager<K, P, T> of(Function<P, K> keyExtractor,
                                                      Function<P, T> singletonCreator,
                                                      BiFunction<P, Map<String, ?>, T> configuredCreator,
                                                      P... providers) {
        KeyedRegistry<K, P> registry = KeyedRegistry.from(Arrays.asList(providers), keyExtractor, "test");
        return from(registry, singletonCreator, configuredCreator);
    }

    public T get(K key) {
        return factory.get(key);
    }

    public T create(K key) {
        return factory.create(key);
    }

    public T create(K key, Map<String, ?> config) {
        if (config == null || config.isEmpty()) {
            return get(key);
        }
        return configuredCreator.apply(registry.get(key), config);
    }

    public T getIfLoaded(K key) {
        return factory.getIfLoaded(key);
    }

    public boolean supports(K key) {
        return registry.supports(key);
    }

    public P getProvider(K key) {
        return registry.get(key);
    }

    public P findProvider(K key) {
        return registry.find(key);
    }

    public Set<K> getSupportedKeys() {
        return registry.getSupportedKeys();
    }

    public Collection<P> getSupportedProviders() {
        return registry.providers();
    }

    public Set<K> getLoadedKeys() {
        return factory.loadedKeys();
    }

    public List<T> getLoadedInstances() {
        return factory.loadedInstances();
    }

    public void clearCache() {
        factory.clear();
    }

    public void evict(K key) {
        factory.evict(key);
    }
}
