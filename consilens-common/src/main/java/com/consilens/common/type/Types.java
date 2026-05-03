package com.consilens.common.type;

import com.consilens.common.enums.DataType;

import java.util.Arrays;
import java.util.List;

/**
 * Factory helpers for common logical types.
 */
public final class Types {

    private Types() {
    }

    public static TypeDescriptor NULL() {
        return TypeDescriptor.builder(DataType.NULL_TYPE).build();
    }

    public static TypeDescriptor BOOLEAN() {
        return TypeDescriptor.builder(DataType.BOOLEAN_TYPE).build();
    }

    public static TypeDescriptor TINYINT() {
        return INTEGER(8).toBuilder().originType("TINYINT").build();
    }

    public static TypeDescriptor SMALLINT() {
        return INTEGER(16).toBuilder().originType("SMALLINT").build();
    }

    public static TypeDescriptor INT() {
        return INTEGER(32).toBuilder().originType("INT").build();
    }

    public static TypeDescriptor BIGINT() {
        return INTEGER(64).toBuilder().originType("BIGINT").build();
    }

    public static TypeDescriptor INTEGER(int bitWidth) {
        return TypeDescriptor.builder(DataType.INTEGER_TYPE)
                .bitWidth(bitWidth)
                .build();
    }

    public static TypeDescriptor INTEGER(int bitWidth, boolean unsigned) {
        return TypeDescriptor.builder(DataType.INTEGER_TYPE)
                .bitWidth(bitWidth)
                .unsigned(unsigned)
                .build();
    }

    public static TypeDescriptor FLOAT() {
        return TypeDescriptor.builder(DataType.FLOAT_TYPE)
                .originType("FLOAT")
                .build();
    }

    public static TypeDescriptor DOUBLE() {
        return TypeDescriptor.builder(DataType.DOUBLE_TYPE)
                .originType("DOUBLE")
                .build();
    }

    public static TypeDescriptor DECIMAL(int precision, int scale) {
        return TypeDescriptor.builder(DataType.DECIMAL_TYPE)
                .originType("DECIMAL(" + precision + "," + scale + ")")
                .numericPrecision(precision)
                .numericScale(scale)
                .build();
    }

    public static TypeDescriptor STRING() {
        return TypeDescriptor.builder(DataType.STRING_TYPE).build();
    }

    public static TypeDescriptor STRING(int length) {
        return TypeDescriptor.builder(DataType.STRING_TYPE)
                .length(length)
                .build();
    }

    public static TypeDescriptor VARCHAR(int length) {
        return STRING(length).toBuilder()
                .originType("VARCHAR(" + length + ")")
                .build();
    }

    public static TypeDescriptor CHAR(int length) {
        return STRING(length).toBuilder()
                .originType("CHAR(" + length + ")")
                .build();
    }

    public static TypeDescriptor TEXT() {
        return TypeDescriptor.builder(DataType.STRING_TYPE)
                .originType("TEXT")
                .textType(true)
                .build();
    }

    public static TypeDescriptor BINARY() {
        return TypeDescriptor.builder(DataType.BINARY_TYPE).build();
    }

    public static TypeDescriptor BINARY(int length) {
        return TypeDescriptor.builder(DataType.BINARY_TYPE)
                .length(length)
                .build();
    }

    public static TypeDescriptor VARBINARY(int length) {
        return BINARY(length).toBuilder()
                .originType("VARBINARY(" + length + ")")
                .build();
    }

    public static TypeDescriptor BLOB() {
        return TypeDescriptor.builder(DataType.BINARY_TYPE)
                .originType("BLOB")
                .blobType(true)
                .build();
    }

    public static TypeDescriptor DATE() {
        return TypeDescriptor.builder(DataType.DATE_TYPE).build();
    }

    public static TypeDescriptor TIME() {
        return TypeDescriptor.builder(DataType.TIME_TYPE).build();
    }

    public static TypeDescriptor TIME(int precision) {
        return TypeDescriptor.builder(DataType.TIME_TYPE)
                .timePrecision(precision)
                .build();
    }

    public static TypeDescriptor TIMESTAMP() {
        return TypeDescriptor.builder(DataType.TIMESTAMP_TYPE).build();
    }

    public static TypeDescriptor TIMESTAMP(int precision) {
        return TypeDescriptor.builder(DataType.TIMESTAMP_TYPE)
                .timePrecision(precision)
                .build();
    }

    public static TypeDescriptor TIMESTAMP(int precision, boolean withTimezone) {
        return TypeDescriptor.builder(DataType.TIMESTAMP_TYPE)
                .timePrecision(precision)
                .withTimezone(withTimezone)
                .build();
    }

    public static TypeDescriptor ARRAY(TypeDescriptor elementType) {
        return TypeDescriptor.builder(DataType.ARRAY_TYPE)
                .elementType(elementType)
                .build();
    }

    public static TypeDescriptor MAP(TypeDescriptor keyType, TypeDescriptor valueType) {
        return TypeDescriptor.builder(DataType.MAP_TYPE)
                .keyType(keyType)
                .valueType(valueType)
                .build();
    }

    public static TypeDescriptor STRUCT(StructField... fields) {
        return STRUCT(Arrays.asList(fields));
    }

    public static TypeDescriptor STRUCT(List<StructField> fields) {
        return TypeDescriptor.builder(DataType.STRUCT_TYPE)
                .fields(fields)
                .build();
    }

    public static TypeDescriptor JSON() {
        return TypeDescriptor.builder(DataType.JSON_TYPE).build();
    }

    public static TypeDescriptor XML() {
        return TypeDescriptor.builder(DataType.XML_TYPE).build();
    }

    public static TypeDescriptor UUID() {
        return TypeDescriptor.builder(DataType.UUID_TYPE).build();
    }

    public static TypeDescriptor ENUM(List<String> enumValues) {
        return TypeDescriptor.builder(DataType.ENUM_TYPE)
                .enumValues(enumValues)
                .build();
    }

    public static TypeDescriptor OBJECT() {
        return TypeDescriptor.builder(DataType.OBJECT_TYPE).build();
    }

    public static TypeDescriptor UNKNOWN() {
        return TypeDescriptor.builder(DataType.UNKNOWN_TYPE).build();
    }
}
