package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class LifecyclePolicyDTO {

    @NotBlank(message = "策略名称不能为空")
    private String policyName;

    @NotBlank(message = "数据源ID不能为空")
    private String datasourceId;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    @NotNull(message = "热存储天数不能为空")
    private Integer hotStorageDays;

    @NotNull(message = "冷存储天数不能为空")
    private Integer coldStorageDays;

    @NotNull(message = "归档存储天数不能为空")
    private Integer archiveStorageDays;

    private Boolean enabled = true;
}
