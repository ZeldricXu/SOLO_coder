package com.modelguard.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class AbExperimentDTO implements Serializable {

    @NotBlank(message = "实验名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "对照组PromptID不能为空")
    private String controlGroupPromptId;

    @NotNull(message = "对照组Prompt版本不能为空")
    private Integer controlGroupPromptVersion;

    @NotBlank(message = "实验组PromptID不能为空")
    private String experimentGroupPromptId;

    @NotNull(message = "实验组Prompt版本不能为空")
    private Integer experimentGroupPromptVersion;

    @DecimalMin(value = "0.1", message = "流量分配比例最小为0.1")
    @DecimalMax(value = "0.9", message = "流量分配比例最大为0.9")
    private BigDecimal trafficSplit = new BigDecimal("0.5");

    private String createdBy;
}
