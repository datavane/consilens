package com.consilens.connector.api;

import com.consilens.common.enums.DataType;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.common.type.Types;

/**
 * Converts between connector-native types and logical type descriptors.
 */
public interface TypeConverter {

    @Deprecated
    default DataType convert(String originType) {
        return convertToTypeDescriptor(originType).getType();
    }

    @Deprecated
    default String convertToOriginType(DataType dataType) {
        switch (dataType) {
            case DECIMAL_TYPE:
                return convertToOriginType(Types.DECIMAL(38, 0));
            case ARRAY_TYPE:
                return convertToOriginType(Types.ARRAY(Types.UNKNOWN()));
            case MAP_TYPE:
                return convertToOriginType(Types.MAP(Types.STRING(), Types.UNKNOWN()));
            case STRUCT_TYPE:
                throw new UnsupportedOperationException("STRUCT_TYPE requires field definitions");
            default:
                return convertToOriginType(TypeDescriptor.builder(dataType).build());
        }
    }

    TypeDescriptor convertToTypeDescriptor(String originType);

    String convertToOriginType(TypeDescriptor typeDescriptor);

    default boolean isCompatible(TypeDescriptor source, TypeDescriptor target) {
        if (source == null || target == null) {
            return false;
        }
        if (source.getType() == target.getType()) {
            return isSameFamilyCompatible(source, target);
        }
        if (source.getType().isNumeric() && target.getType().isNumeric()) {
            return true;
        }
        return source.getType() == DataType.STRING_TYPE && target.getType() == DataType.STRING_TYPE;
    }

    default boolean isSameFamilyCompatible(TypeDescriptor source, TypeDescriptor target) {
        if (source.getType() == DataType.INTEGER_TYPE) {
            Integer sourceBits = source.getBitWidth();
            Integer targetBits = target.getBitWidth();
            return sourceBits == null || targetBits == null || sourceBits <= targetBits;
        }
        if (source.getType() == DataType.DECIMAL_TYPE) {
            Integer sourcePrecision = source.getNumericPrecision();
            Integer targetPrecision = target.getNumericPrecision();
            Integer sourceScale = source.getNumericScale();
            Integer targetScale = target.getNumericScale();
            boolean precisionCompatible = sourcePrecision == null || targetPrecision == null || sourcePrecision <= targetPrecision;
            boolean scaleCompatible = sourceScale == null || targetScale == null || sourceScale <= targetScale;
            return precisionCompatible && scaleCompatible;
        }
        if (source.getType() == DataType.STRING_TYPE || source.getType() == DataType.BINARY_TYPE) {
            Integer sourceLength = source.getLength();
            Integer targetLength = target.getLength();
            return sourceLength == null || targetLength == null || sourceLength <= targetLength;
        }
        if (source.getType() == DataType.TIMESTAMP_TYPE || source.getType() == DataType.TIME_TYPE) {
            Integer sourcePrecision = source.getTimePrecision();
            Integer targetPrecision = target.getTimePrecision();
            return sourcePrecision == null || targetPrecision == null || sourcePrecision <= targetPrecision;
        }
        return true;
    }
}
