package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceRecord {

    private Long id;
    private String name;
    private String type;
    private String paramJson;
    private Instant createdAt;
    private Instant updatedAt;
}
