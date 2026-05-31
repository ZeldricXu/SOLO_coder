package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class ExperimentResultRecordDTO implements Serializable {

    @NotBlank(message = "实验ID不能为空")
    private String experimentId;

    @NotBlank(message = "分组类型不能为空")
    private String groupType;

    @NotNull(message = "总请求数不能为空")
    private Long totalRequests;

    @NotNull(message = "成功数不能为空")
    private Long successCount;

    private BigDecimal avgLatencyMs;

    private BigDecimal p99LatencyMs;

    private BigDecimal errorRate;

    private BigDecimal satisfactionScore;

    private ObjectNode metrics;
}
