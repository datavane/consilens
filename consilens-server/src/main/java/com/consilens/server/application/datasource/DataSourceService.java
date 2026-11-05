package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.connector.api.DataSourceField;

import java.util.List;
import java.util.Optional;

public interface DataSourceService {

    List<DataSourceTypeDto> listTypes();

    /** Datasource parameter template (form fields) for a connector type. */
    List<DataSourceField> getTypeConfig(String type);

    DataSourceDto create(DataSourceCreateRequest request);

    List<DataSourceDto> list();

    /** 分页列表（列表页默认每页 10 条）。 */
    PageResponse<DataSourceDto> listPage(int page, int pageSize);

    DataSourceDto get(Long id);

    /** Exact-name lookup for agent dedup; the DTO never contains the password. */
    Optional<DataSourceDto> findByName(String name);

    DataSourceDto update(Long id, DataSourceCreateRequest request);

    void delete(Long id);

    /** Test connectivity using the stored parameters. */
    ConnectionTestResponse test(Long id);

    /** Live metadata exploration (reuses stored parameters + connector dialect). */
    List<String> getDatabases(Long id);

    List<String> getTables(Long id, String database);

    List<MetadataColumnDto> getColumns(Long id, String database, String table);

    /** 主键列名（agent 建比对任务时用于 keys）。 */
    List<String> getPrimaryKeys(Long id, String database, String table);
}
