package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class HeartbeatDTO implements Serializable {

    @NotBlank(message = "节点ID不能为空")
    private String nodeId;

    @NotNull(message = "可用GPU显存不能为空")
    private BigDecimal availableGpuMemoryGb;

    private ObjectNode metrics;
}
