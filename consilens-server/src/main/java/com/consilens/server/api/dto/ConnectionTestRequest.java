package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTestRequest {

    @NotBlank(message = "type is required")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "type contains invalid characters")
    private String type;

    @NotBlank(message = "host is required")
    @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}", message = "host contains invalid characters")
    private String host;

    private Integer port;

    @Pattern(regexp = "[A-Za-z0-9_.-]{0,128}", message = "database contains invalid characters")
    private String database;

    private String username;

    private String password;

    /**
     * 编辑态测连：主机/库/用户名取当前表单值，密码留空时回填该数据源已保存的密码。
     */
    private Long datasourceId;

    private Map<String, Object> options;
}
