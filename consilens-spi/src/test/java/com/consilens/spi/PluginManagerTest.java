package com.consilens.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerTest {

    @Test
    void shouldCacheSingletonInstances() {
        PluginManager<String, FakeProvider, FakeService> manager = PluginManager.of(
                FakeProvider::key,
                FakeProvider::create,
                FakeProvider::create,
                new FakeProvider("mysql"));

        FakeService first = manager.get("mysql");
        FakeService second = manager.get("mysql");

        assertSame(first, second);
        assertTrue(manager.getLoadedKeys().contains("mysql"));
        assertSame(first, manager.getIfLoaded("mysql"));
    }

    @Test
    void shouldCreateNewConfiguredInstances() {
        PluginManager<String, FakeProvider, FakeService> manager = PluginManager.of(
                FakeProvider::key,
                FakeProvider::create,
                FakeProvider::create,
                new FakeProvider("mysql"));

        FakeService first = manager.create("mysql", Map.of("precision", 6));
        FakeService second = manager.create("mysql", Map.of("precision", 6));

        assertNotSame(first, second);
        assertNotNull(first.config());
        assertEquals(6, first.config().get("precision"));
    }

    @Test
    void shouldNotLoadInstanceWhenUsingGetIfLoaded() {
        PluginManager<String, FakeProvider, FakeService> manager = PluginManager.of(
                FakeProvider::key,
                FakeProvider::create,
                FakeProvider::create,
                new FakeProvider("mysql"));

        assertNull(manager.getIfLoaded("mysql"));
        assertTrue(manager.getLoadedInstances().isEmpty());
    }

    private static final class FakeProvider {
        private final String key;

        private FakeProvider(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        FakeService create() {
            return new FakeService(key, null);
        }

        FakeService create(Map<String, ?> config) {
            return new FakeService(key, config);
        }
    }

    private static final class FakeService {
        private final String key;
        private final Map<String, ?> config;

        private FakeService(String key, Map<String, ?> config) {
            this.key = key;
            this.config = config;
        }

        String key() {
            return key;
        }

        Map<String, ?> config() {
            return config;
        }
    }
}
