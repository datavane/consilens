package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.DataSourcePage;
import com.consilens.server.domain.model.DataSourceRecord;

import java.util.List;
import java.util.Optional;

public interface DataSourceRepository {

    DataSourceRecord save(DataSourceRecord record);

    Optional<DataSourceRecord> findById(Long id);

    Optional<DataSourceRecord> findByName(String name);

    List<DataSourceRecord> listAll();

    /** 分页列表（按名称升序）。 */
    DataSourcePage listPage(int page, int pageSize);

    void deleteById(Long id);
}
