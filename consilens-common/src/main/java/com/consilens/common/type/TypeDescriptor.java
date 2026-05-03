package com.consilens.common.type;

import com.consilens.common.enums.DataType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable logical type descriptor carrying family and storage details.
 */
public final class TypeDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final DataType type;
    private final String originType;
    private final boolean nullable;

    private final Integer bitWidth;
    private final boolean unsigned;
    private final Integer numericPrecision;
    private final Integer numericScale;

    private final Integer length;
    private final String charset;
    private final String collation;
    private final boolean textType;
    private final boolean blobType;

    private final boolean withTimezone;
    private final Integer timePrecision;

    private final TypeDescriptor elementType;
    private final TypeDescriptor keyType;
    private final TypeDescriptor valueType;
    private final List<StructField> fields;

    private final List<String> enumValues;
    private final Integer srid;
    private final String comment;

    private TypeDescriptor(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.originType = builder.originType;
        this.nullable = builder.nullable;
        this.bitWidth = builder.bitWidth;
        this.unsigned = builder.unsigned;
        this.numericPrecision = builder.numericPrecision;
        this.numericScale = builder.numericScale;
        this.length = builder.length;
        this.charset = builder.charset;
        this.collation = builder.collation;
        this.textType = builder.textType;
        this.blobType = builder.blobType;
        this.withTimezone = builder.withTimezone;
        this.timePrecision = builder.timePrecision;
        this.elementType = builder.elementType;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
        this.fields = immutableCopy(builder.fields);
        this.enumValues = immutableCopy(builder.enumValues);
        this.srid = builder.srid;
        this.comment = builder.comment;
        validate();
    }

    public static Builder builder(DataType type) {
        return new Builder(type);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private void validate() {
        if (type == DataType.DECIMAL_TYPE && numericPrecision == null) {
            throw new IllegalArgumentException("DECIMAL_TYPE requires numericPrecision");
        }
        if (type == DataType.ARRAY_TYPE && elementType == null) {
            throw new IllegalArgumentException("ARRAY_TYPE requires elementType");
        }
        if (type == DataType.MAP_TYPE && (keyType == null || valueType == null)) {
            throw new IllegalArgumentException("MAP_TYPE requires keyType and valueType");
        }
        if (type == DataType.STRUCT_TYPE && (fields == null || fields.isEmpty())) {
            throw new IllegalArgumentException("STRUCT_TYPE requires fields");
        }
    }

    public DataType getType() {
        return type;
    }

    public String getOriginType() {
        return originType;
    }

    public boolean isNullable() {
        return nullable;
    }

    public Integer getBitWidth() {
        return bitWidth;
    }

    public boolean isUnsigned() {
        return unsigned;
    }

    public Integer getNumericPrecision() {
        return numericPrecision;
    }

    public Integer getNumericScale() {
        return numericScale;
    }

    public Integer getLength() {
        return length;
    }

    public String getCharset() {
        return charset;
    }

    public String getCollation() {
        return collation;
    }

    public boolean isTextType() {
        return textType;
    }

    public boolean isBlobType() {
        return blobType;
    }

    public boolean isWithTimezone() {
        return withTimezone;
    }

    public Integer getTimePrecision() {
        return timePrecision;
    }

    public TypeDescriptor getElementType() {
        return elementType;
    }

    public TypeDescriptor getKeyType() {
        return keyType;
    }

    public TypeDescriptor getValueType() {
        return valueType;
    }

    public List<StructField> getFields() {
        return fields;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public Integer getSrid() {
        return srid;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypeDescriptor)) {
            return false;
        }
        TypeDescriptor that = (TypeDescriptor) o;
        return nullable == that.nullable
                && unsigned == that.unsigned
                && textType == that.textType
                && blobType == that.blobType
                && withTimezone == that.withTimezone
                && type == that.type
                && Objects.equals(originType, that.originType)
                && Objects.equals(bitWidth, that.bitWidth)
                && Objects.equals(numericPrecision, that.numericPrecision)
                && Objects.equals(numericScale, that.numericScale)
                && Objects.equals(length, that.length)
                && Objects.equals(charset, that.charset)
                && Objects.equals(collation, that.collation)
                && Objects.equals(timePrecision, that.timePrecision)
                && Objects.equals(elementType, that.elementType)
                && Objects.equals(keyType, that.keyType)
                && Objects.equals(valueType, that.valueType)
                && Objects.equals(fields, that.fields)
                && Objects.equals(enumValues, that.enumValues)
                && Objects.equals(srid, that.srid)
                && Objects.equals(comment, that.comment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, originType, nullable, bitWidth, unsigned, numericPrecision, numericScale,
                length, charset, collation, textType, blobType, withTimezone, timePrecision, elementType,
                keyType, valueType, fields, enumValues, srid, comment);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type.getTypeName());
        if (originType != null && !originType.isBlank()) {
            sb.append('(').append(originType).append(')');
        }
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }

    public static final class Builder {
        private final DataType type;
        private String originType;
        private boolean nullable = true;
        private Integer bitWidth;
        private boolean unsigned;
        private Integer numericPrecision;
        private Integer numericScale;
        private Integer length;
        private String charset;
        private String collation;
        private boolean textType;
        private boolean blobType;
        private boolean withTimezone;
        private Integer timePrecision;
        private TypeDescriptor elementType;
        private TypeDescriptor keyType;
        private TypeDescriptor valueType;
        private List<StructField> fields;
        private List<String> enumValues;
        private Integer srid;
        private String comment;

        private Builder(DataType type) {
            this.type = type;
        }

        private Builder(TypeDescriptor descriptor) {
            this.type = descriptor.type;
            this.originType = descriptor.originType;
            this.nullable = descriptor.nullable;
            this.bitWidth = descriptor.bitWidth;
            this.unsigned = descriptor.unsigned;
            this.numericPrecision = descriptor.numericPrecision;
            this.numericScale = descriptor.numericScale;
            this.length = descriptor.length;
            this.charset = descriptor.charset;
            this.collation = descriptor.collation;
            this.textType = descriptor.textType;
            this.blobType = descriptor.blobType;
            this.withTimezone = descriptor.withTimezone;
            this.timePrecision = descriptor.timePrecision;
            this.elementType = descriptor.elementType;
            this.keyType = descriptor.keyType;
            this.valueType = descriptor.valueType;
            this.fields = descriptor.fields;
            this.enumValues = descriptor.enumValues;
            this.srid = descriptor.srid;
            this.comment = descriptor.comment;
        }

        public Builder originType(String originType) {
            this.originType = originType;
            return this;
        }

        public Builder nullable(boolean nullable) {
            this.nullable = nullable;
            return this;
        }

        public Builder bitWidth(Integer bitWidth) {
            this.bitWidth = bitWidth;
            return this;
        }

        public Builder unsigned(boolean unsigned) {
            this.unsigned = unsigned;
            return this;
        }

        public Builder numericPrecision(Integer numericPrecision) {
            this.numericPrecision = numericPrecision;
            return this;
        }

        public Builder numericScale(Integer numericScale) {
            this.numericScale = numericScale;
            return this;
        }

        public Builder length(Integer length) {
            this.length = length;
            return this;
        }

        public Builder charset(String charset) {
            this.charset = charset;
            return this;
        }

        public Builder collation(String collation) {
            this.collation = collation;
            return this;
        }

        public Builder textType(boolean textType) {
            this.textType = textType;
            return this;
        }

        public Builder blobType(boolean blobType) {
            this.blobType = blobType;
            return this;
        }

        public Builder withTimezone(boolean withTimezone) {
            this.withTimezone = withTimezone;
            return this;
        }

        public Builder timePrecision(Integer timePrecision) {
            this.timePrecision = timePrecision;
            return this;
        }

        public Builder elementType(TypeDescriptor elementType) {
            this.elementType = elementType;
            return this;
        }

        public Builder keyType(TypeDescriptor keyType) {
            this.keyType = keyType;
            return this;
        }

        public Builder valueType(TypeDescriptor valueType) {
            this.valueType = valueType;
            return this;
        }

        public Builder fields(List<StructField> fields) {
            this.fields = fields;
            return this;
        }

        public Builder enumValues(List<String> enumValues) {
            this.enumValues = enumValues;
            return this;
        }

        public Builder srid(Integer srid) {
            this.srid = srid;
            return this;
        }

        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        public TypeDescriptor build() {
            return new TypeDescriptor(this);
        }
    }
}
