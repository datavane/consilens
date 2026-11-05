package com.consilens.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedRegistryTest {

    @Test
    void shouldRegisterAndFindProviders() {
        KeyedRegistry<String, FakeProvider> registry = registryOf(
                new FakeProvider("mysql"),
                new FakeProvider("postgresql"));

        assertTrue(registry.supports("mysql"));
        assertEquals("mysql", registry.get("mysql").key());
        assertEquals(2, registry.getSupportedKeys().size());
    }

    @Test
    void shouldRejectDuplicateKeys() {
        assertThrows(DuplicateProviderException.class, () -> registryOf(
                new FakeProvider("mysql"),
                new FakeProvider("mysql")));
    }

    @Test
    void shouldThrowWhenProviderMissing() {
        KeyedRegistry<String, FakeProvider> registry = registryOf(new FakeProvider("mysql"));

        assertFalse(registry.supports("oracle"));
        assertThrows(ProviderNotFoundException.class, () -> registry.get("oracle"));
    }

    private static KeyedRegistry<String, FakeProvider> registryOf(FakeProvider... providers) {
        return KeyedRegistry.from(List.of(providers), FakeProvider::key, "test");
    }

    private static final class FakeProvider {
        private final String key;

        private FakeProvider(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }
}
