package com.consilens.connector.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDescriptor {

    private List<FieldDescriptor> fields;

    private Map<String, FieldDescriptor> fieldMap;
}
