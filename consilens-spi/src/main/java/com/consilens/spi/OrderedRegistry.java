package com.consilens.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class OrderedRegistry<P> {

    private static final Logger log = LoggerFactory.getLogger(OrderedRegistry.class);

    private final List<P> providers;

    private OrderedRegistry(List<P> providers) {
        this.providers = Collections.unmodifiableList(providers);
    }

    public static <P> OrderedRegistry<P> load(Class<P> providerType, Comparator<P> ordering) {
        return from(ServiceLoaderUtils.loadAll(providerType), ordering, providerType.getSimpleName());
    }

    @SafeVarargs
    public static <P> OrderedRegistry<P> of(Comparator<P> ordering, P... providers) {
        return from(Arrays.asList(providers), ordering, "test");
    }

    public static <P> OrderedRegistry<P> from(Iterable<P> providers,
                                              Comparator<P> ordering,
                                              String registryName) {
        List<P> sorted = StreamSupport.stream(providers.spliterator(), false)
                .sorted(ordering)
                .collect(Collectors.toList());
        for (int i = 0; i < sorted.size(); i++) {
            log.info("[{}] Registered [order={}]: {}", registryName, i, sorted.get(i).getClass().getName());
        }
        log.info("[{}] Initialized with {} providers", registryName, sorted.size());
        return new OrderedRegistry<>(sorted);
    }

    public List<P> getAll() {
        return providers;
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    public int size() {
        return providers.size();
    }
}
