package com.consilens.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedRegistryTest {

    @Test
    void shouldRegisterAndFindProviders() {
        KeyedRegistry<String, FakeProvider> registry = KeyedRegistry.of(FakeProvider::key,
                new FakeProvider("mysql"),
                new FakeProvider("postgresql"));

        assertTrue(registry.supports("mysql"));
        assertEquals("mysql", registry.get("mysql").key());
        assertEquals(2, registry.getSupportedKeys().size());
    }

    @Test
    void shouldRejectDuplicateKeys() {
        assertThrows(DuplicateProviderException.class, () -> KeyedRegistry.of(FakeProvider::key,
                new FakeProvider("mysql"),
                new FakeProvider("mysql")));
    }

    @Test
    void shouldThrowWhenProviderMissing() {
        KeyedRegistry<String, FakeProvider> registry = KeyedRegistry.of(FakeProvider::key,
                new FakeProvider("mysql"));

        assertFalse(registry.supports("oracle"));
        assertThrows(ProviderNotFoundException.class, () -> registry.get("oracle"));
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
