package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataSourceControllerTest {

    private DataSourceService dataSourceService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dataSourceService = mock(DataSourceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DataSourceController(dataSourceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    @Test
    void shouldListTypes() throws Exception {
        when(dataSourceService.listTypes()).thenReturn(List.of(
                DataSourceTypeDto.builder().type("mysql").defaultPort(3306)
                        .driverClass("com.mysql.cj.jdbc.Driver").build(),
                DataSourceTypeDto.builder().type("postgresql").defaultPort(5432)
                        .driverClass("org.postgresql.Driver").build()));

        mockMvc.perform(get("/v1/datasources/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].type").value("mysql"))
                .andExpect(jsonPath("$.data[0].defaultPort").value(3306))
                .andExpect(jsonPath("$.data[1].type").value("postgresql"));
    }

    @Test
    void shouldListDatasources() throws Exception {
        when(dataSourceService.list()).thenReturn(List.of(dto()));

        mockMvc.perform(get("/v1/datasources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("orders-db"))
                .andExpect(jsonPath("$.data[0].type").value("mysql"))
                .andExpect(jsonPath("$.data[0].param").doesNotExist());
    }

    @Test
    void shouldListDatasourcesPage() throws Exception {
        PageResponse<DataSourceDto> page = PageResponse.<DataSourceDto>builder()
                .total(1)
                .page(1)
                .pageSize(10)
                .items(List.of(dto()))
                .build();
        when(dataSourceService.listPage(1, 10)).thenReturn(page);

        mockMvc.perform(get("/v1/datasources/page").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.items[0].name").value("orders-db"))
                .andExpect(jsonPath("$.data.items[0].param").doesNotExist());
    }

    @Test
    void shouldCreateDatasource() throws Exception {
        when(dataSourceService.create(any(DataSourceCreateRequest.class))).thenReturn(dto());

        mockMvc.perform(post("/v1/datasources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orders-db\",\"type\":\"mysql\","
                                + "\"param\":{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("1"));
    }

    @Test
    void shouldRejectInvalidCreateRequest() throws Exception {
        mockMvc.perform(post("/v1/datasources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad name!\",\"type\":\"mysql\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    void shouldGetDatasource() throws Exception {
        when(dataSourceService.get(1L)).thenReturn(dto());

        mockMvc.perform(get("/v1/datasources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("orders-db"));
    }

    @Test
    void shouldReturnNotFoundForMissingDatasource() throws Exception {
        when(dataSourceService.get(eq(404L))).thenThrow(new ResourceNotFoundException("datasource not found: 404"));

        mockMvc.perform(get("/v1/datasources/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldDeleteDatasource() throws Exception {
        mockMvc.perform(delete("/v1/datasources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shouldReturnTypeConfigTemplate() throws Exception {
        when(dataSourceService.getTypeConfig("postgresql")).thenReturn(List.of(
                com.consilens.connector.api.DataSourceField.builder()
                        .field("host").title("地址").type("input").required(true).build(),
                com.consilens.connector.api.DataSourceField.builder()
                        .field("schema").title("模式").type("input").required(true)
                        .defaultValue("public").build()));

        mockMvc.perform(get("/v1/datasources/types/postgresql/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].field").value("host"))
                .andExpect(jsonPath("$.data[0].required").value(true))
                .andExpect(jsonPath("$.data[1].field").value("schema"))
                .andExpect(jsonPath("$.data[1].defaultValue").value("public"));
    }

    @Test
    void shouldTestDatasource() throws Exception {
        when(dataSourceService.test(1L)).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(12L).build());

        mockMvc.perform(post("/v1/datasources/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.latencyMs").value(12));
    }

    @Test
    void shouldListDatabases() throws Exception {
        when(dataSourceService.getDatabases(1L)).thenReturn(List.of("orders", "inventory"));

        mockMvc.perform(get("/v1/datasources/1/databases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("orders"))
                .andExpect(jsonPath("$.data[1]").value("inventory"));
    }

    @Test
    void shouldListTables() throws Exception {
        when(dataSourceService.getTables(1L, "orders")).thenReturn(List.of("t1", "t2"));

        mockMvc.perform(get("/v1/datasources/1/databases/orders/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("t1"));
    }

    @Test
    void shouldListColumns() throws Exception {
        when(dataSourceService.getColumns(1L, "orders", "t1")).thenReturn(List.of(
                MetadataColumnDto.builder().name("id").dataType("bigint").nullable(false).build()));

        mockMvc.perform(get("/v1/datasources/1/databases/orders/tables/t1/columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("id"))
                .andExpect(jsonPath("$.data[0].dataType").value("bigint"))
                .andExpect(jsonPath("$.data[0].nullable").value(false));
    }

    private DataSourceDto dto() {
        return DataSourceDto.builder()
                .id("1")
                .name("orders-db")
                .type("mysql")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
