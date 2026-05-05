package com.consilens.cli.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NormalizationTypeMapperTest {

    @Test
    void shouldMapCommonAliasesToNormalizationMatchTypes() {
        assertEquals("time_with_timezone", NormalizationTypeMapper.toMatchType("time_with_time_zone"));
        assertEquals("time_with_timezone", NormalizationTypeMapper.toMatchType("TIME WITH TIME ZONE"));
        assertEquals("timestamp_with_timezone", NormalizationTypeMapper.toMatchType("timestamp_with_time_zone"));
        assertEquals("timestamp_with_timezone", NormalizationTypeMapper.toMatchType("timestamptz"));
        assertEquals("datetime", NormalizationTypeMapper.toMatchType("datetime2"));
        assertEquals("boolean", NormalizationTypeMapper.toMatchType("bool"));
    }

    @Test
    void shouldKeepUnknownTypeAfterNormalization() {
        assertEquals("custom type", NormalizationTypeMapper.toMatchType("custom_type"));
        assertNull(NormalizationTypeMapper.toMatchType(" "));
    }
}
