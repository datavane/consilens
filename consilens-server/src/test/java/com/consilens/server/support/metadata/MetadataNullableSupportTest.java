package com.consilens.server.support.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataNullableSupportTest {

    @Test
    void parsesInformationSchemaAndOracleForms() {
        assertEquals(Boolean.FALSE, MetadataNullableSupport.parse("NO"));
        assertEquals(Boolean.FALSE, MetadataNullableSupport.parse("N"));
        assertEquals(Boolean.FALSE, MetadataNullableSupport.parse("0"));
        assertEquals(Boolean.TRUE, MetadataNullableSupport.parse("YES"));
        assertEquals(Boolean.TRUE, MetadataNullableSupport.parse("Y"));
        assertEquals(Boolean.TRUE, MetadataNullableSupport.parse("1"));
        assertEquals(Boolean.TRUE, MetadataNullableSupport.parse(" yes "));
    }

    @Test
    void treatsMissingMetadataAsUnknown() {
        assertNull(MetadataNullableSupport.parse(null));
        assertNull(MetadataNullableSupport.parse(""));
        assertNull(MetadataNullableSupport.parse("  "));
    }
}
