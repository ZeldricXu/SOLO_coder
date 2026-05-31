package com.iotplatform.edgeinference.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class InferenceResultDTO {

    @NotBlank(message = "任务ID不能为空")
    private String taskId;

    private String outputData;

    private String outputPath;

    private BigDecimal progress;

    private String status;

    private String errorDetail;

    private LocalDateTime completedAt;

    private Long inferenceTimeMs;

    private Double confidence;
}
