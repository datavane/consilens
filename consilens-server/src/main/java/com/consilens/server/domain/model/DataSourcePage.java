package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourcePage {

    private long total;
    private List<DataSourceRecord> items;
}
