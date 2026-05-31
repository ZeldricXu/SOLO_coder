package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
public class DatasourceDTO {

    @NotBlank(message = "数据源名称不能为空")
    private String datasourceName;

    @NotBlank(message = "数据源类型不能为空")
    private String datasourceType;

    @NotNull(message = "连接配置不能为空")
    private Map<String, Object> connectionConfig;
}
