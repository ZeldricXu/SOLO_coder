package com.datastandard.modules.slo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SloDefinitionRequest {

    @NotBlank(message = "SLO名称不能为空")
    private String sloName;

    private String sloDescription;

    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    @NotBlank(message = "环境标识不能为空")
    private String environment;

    @NotBlank(message = "SLI类型不能为空")
    private String sliType;

    @NotNull(message = "目标值不能为空")
    @Positive(message = "目标值必须为正数")
    private Double targetValue;

    @NotBlank(message = "目标方向不能为空")
    private String targetDirection;

    @NotNull(message = "时间窗口不能为空")
    private Duration timeWindow;

    @Valid
    private List<AlertThreshold> alertThresholds;

    private Map<String, String> labels;

    private String createdBy;

    private boolean enabled;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertThreshold {
        @NotBlank(message = "告警级别不能为空")
        private String level;

        @NotNull(message = "消耗阈值不能为空")
        @Positive(message = "消耗阈值必须为正数")
        private Double burnRateThreshold;

        @NotNull(message = "告警窗口不能为空")
        private Duration windowDuration;

        private String notificationChannel;
    }
}
