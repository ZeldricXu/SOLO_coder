package com.datastandard.modules.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformRequest {

    @NotBlank(message = "数据源标识不能为空")
    private String dataSource;

    @NotBlank(message = "数据集名称不能为空")
    private String datasetName;

    @NotEmpty(message = "待处理数据不能为空")
    @Valid
    private List<Map<String, Object>> records;

    @Valid
    private StandardizationConfig config;

    private String requestId;

    private Instant timestamp;

    private Map<String, Object> metadata;
}
