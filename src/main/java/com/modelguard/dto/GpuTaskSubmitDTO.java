package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class GpuTaskSubmitDTO implements Serializable {

    @NotBlank(message = "任务名称不能为空")
    private String name;

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    @Min(value = 1, message = "优先级最小为1")
    @Max(value = 10, message = "优先级最大为10")
    private Integer priority = 5;

    @NotNull(message = "所需GPU显存不能为空")
    @DecimalMin(value = "0.1", message = "所需GPU显存最小为0.1GB")
    private BigDecimal requiredGpuMemoryGb;

    @Min(value = 1, message = "GPU数量最小为1")
    private Integer gpuCount = 1;

    private Boolean preemptible = true;

    private String command;

    private ObjectNode parameters;

    private String submittedBy;
}
