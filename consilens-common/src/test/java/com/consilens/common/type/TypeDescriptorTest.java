package com.consilens.common.type;

import com.consilens.common.enums.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeDescriptorTest {

    @Test
    void shouldUseSafeDecimalDefaultsWhenMetadataOmitsPrecision() {
        TypeDescriptor descriptor = TypeDescriptor.builder(DataType.DECIMAL_TYPE).build();

        assertEquals(38, descriptor.getNumericPrecision());
        assertEquals(0, descriptor.getNumericScale());
    }
}
