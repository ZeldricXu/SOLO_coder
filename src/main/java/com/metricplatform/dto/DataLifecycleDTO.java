package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

@Data
public class DataLifecycleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    @NotNull(message = "热数据天数不能为空")
    @Positive(message = "热数据天数必须大于0")
    private Integer hotDays;

    @NotNull(message = "温数据天数不能为空")
    @Positive(message = "温数据天数必须大于0")
    private Integer warmDays;

    @NotNull(message = "冷数据天数不能为空")
    @Positive(message = "冷数据天数必须大于0")
    private Integer coldDays;

    private Boolean archiveEnabled = true;

    private Boolean cleanupEnabled = true;

    private String archiveTableSuffix = "_archive";
}
