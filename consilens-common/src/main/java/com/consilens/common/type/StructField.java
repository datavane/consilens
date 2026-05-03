package com.consilens.common.type;

import java.io.Serializable;
import java.util.Objects;

/**
 * Field definition used by structured logical types.
 */
public final class StructField implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final TypeDescriptor typeDescriptor;
    private final String comment;

    private StructField(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.typeDescriptor = Objects.requireNonNull(builder.typeDescriptor, "typeDescriptor cannot be null");
        this.comment = builder.comment;
    }

    public static Builder builder(String name, TypeDescriptor typeDescriptor) {
        return new Builder(name, typeDescriptor);
    }

    public String getName() {
        return name;
    }

    public TypeDescriptor getTypeDescriptor() {
        return typeDescriptor;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StructField)) {
            return false;
        }
        StructField that = (StructField) o;
        return Objects.equals(name, that.name)
                && Objects.equals(typeDescriptor, that.typeDescriptor)
                && Objects.equals(comment, that.comment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeDescriptor, comment);
    }

    public static final class Builder {
        private final String name;
        private final TypeDescriptor typeDescriptor;
        private String comment;

        private Builder(String name, TypeDescriptor typeDescriptor) {
            this.name = name;
            this.typeDescriptor = typeDescriptor;
        }

        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        public StructField build() {
            return new StructField(this);
        }
    }
}
