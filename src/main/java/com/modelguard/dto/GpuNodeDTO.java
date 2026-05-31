package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class GpuNodeDTO implements Serializable {

    @NotBlank(message = "主机名不能为空")
    private String hostname;

    @NotBlank(message = "IP地址不能为空")
    private String ipAddress;

    @NotNull(message = "GPU数量不能为空")
    private Integer gpuCount;

    private String gpuModel;

    @NotNull(message = "总GPU显存不能为空")
    private BigDecimal totalGpuMemoryGb;

    private ObjectNode labels;
}
