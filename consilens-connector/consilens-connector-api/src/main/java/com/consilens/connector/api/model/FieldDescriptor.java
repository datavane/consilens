package com.consilens.connector.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDescriptor {

    private String name;

    private String canonicalType;

    private boolean nullable;

    private Integer ordinal;

    private Map<String, Object> attributes;
}
