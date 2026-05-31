package com.device.platform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class DataPointIngestRequest {
    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotEmpty(message = "points不能为空")
    @Valid
    private List<DataPoint> points;

    @Data
    public static class DataPoint {
        @NotBlank(message = "metricName不能为空")
        private String metricName;

        @NotNull(message = "metricValue不能为空")
        private Double metricValue;

        private String unit;

        @NotNull(message = "collectedAt不能为空")
        private Instant collectedAt;

        private Map<String, String> tags;
    }
}
