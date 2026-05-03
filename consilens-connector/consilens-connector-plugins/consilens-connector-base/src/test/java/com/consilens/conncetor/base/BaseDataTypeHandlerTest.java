package com.consilens.conncetor.base;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseDataTypeHandlerTest {

    private final BaseDataTypeHandler handler = new BaseDataTypeHandler(new TestCapabilityProvider());

    @Test
    void shouldFallbackForUnknownTypeNormalization() {
        assertEquals("COALESCE(TRIM(CAST(\"payload\" AS VARCHAR)), '')",
                handler.normalizeColumn("payload", DataType.UNKNOWN));
        assertEquals("COALESCE(TRIM(CAST(\"payload\" AS VARCHAR)), '')",
                handler.normalizeColumn("payload", null));
    }

    @Test
    void shouldConvertParameterizedAndAliasedTypes() {
        assertEquals(DataType.VARCHAR, handler.convertToDataType("varchar(255)"));
        assertEquals(DataType.DECIMAL, handler.convertToDataType("decimal(10,2)"));
        assertEquals(DataType.TIMESTAMP_WITH_TIMEZONE, handler.convertToDataType("timestamp with time zone"));
        assertEquals(DataType.DOUBLE, handler.convertToDataType("double precision"));
        assertEquals(DataType.BIGINT, handler.convertToDataType("int8"));
        assertEquals(DataType.BOOLEAN, handler.convertToDataType("bool"));
        assertEquals(DataType.CHAR, handler.convertToDataType("bpchar"));
        assertEquals(DataType.VARCHAR, handler.convertToDataType("enum"));
        assertEquals(DataType.VARCHAR, handler.convertToDataType("set"));
    }

    @Test
    void shouldNormalizeBitAsBoolean() {
        assertEquals("CASE WHEN \"flag\" = TRUE THEN '1' ELSE '0' END",
                handler.normalizeColumn("flag", DataType.BIT));
    }

    @Test
    void shouldConvertToTypedDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("decimal(18,4)");

        assertEquals(com.consilens.common.enums.DataType.DECIMAL_TYPE, descriptor.getType());
        assertEquals(18, descriptor.getNumericPrecision());
        assertEquals(4, descriptor.getNumericScale());
        assertEquals("decimal(18,4)", handler.convertToOriginType(descriptor));
    }

    @Test
    void shouldParseTypedStringLength() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("varchar(255)");

        assertEquals(com.consilens.common.enums.DataType.STRING_TYPE, descriptor.getType());
        assertEquals(255, descriptor.getLength());
        assertTrue(handler.convertToOriginType(descriptor).contains("255"));
    }

    private static class TestCapabilityProvider implements CapabilityProvider {
        @Override
        public boolean supportsFeature(DatabaseFeature feature) {
            return false;
        }

        @Override
        public Set<DatabaseFeature> getSupportedFeatures() {
            return Collections.emptySet();
        }

        @Override
        public String getDefaultSchema() {
            return "public";
        }

        @Override
        public String getCatalogSeparator() {
            return ".";
        }

        @Override
        public String getPaginationHint(long offset, long limit) {
            return "";
        }

        @Override
        public char getWildcardEscapeChar() {
            return '\\';
        }

        @Override
        public String escapePattern(String pattern) {
            return pattern;
        }

        @Override
        public String getOpenQuote() {
            return "\"";
        }

        @Override
        public String getCloseQuote() {
            return "\"";
        }
    }
}
