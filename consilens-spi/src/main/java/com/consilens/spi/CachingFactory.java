package com.consilens.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class CachingFactory<K, T> {

    private final ConcurrentHashMap<K, T> cache = new ConcurrentHashMap<>();
    private final Function<K, T> creator;

    public CachingFactory(Function<K, T> creator) {
        this.creator = creator;
    }

    public T get(K key) {
        return cache.computeIfAbsent(key, creator);
    }

    public T create(K key) {
        return creator.apply(key);
    }

    public Set<K> loadedKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(cache.keySet()));
    }

    public List<T> loadedInstances() {
        return Collections.unmodifiableList(new ArrayList<>(cache.values()));
    }

    public T getIfLoaded(K key) {
        return cache.get(key);
    }

    public boolean isLoaded(K key) {
        return cache.containsKey(key);
    }

    public void clear() {
        cache.clear();
    }

    public void evict(K key) {
        cache.remove(key);
    }

    public int cacheSize() {
        return cache.size();
    }
}
