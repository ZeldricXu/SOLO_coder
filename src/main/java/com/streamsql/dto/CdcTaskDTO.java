package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@Data
public class CdcTaskDTO {

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @NotBlank(message = "数据源ID不能为空")
    private String datasourceId;

    private String schemaName;

    private List<String> tableNames;

    @NotBlank(message = "输出类型不能为空")
    private String outputType;

    @NotNull(message = "输出配置不能为空")
    private Map<String, Object> outputConfig;
}
