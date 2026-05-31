package com.parking.platform.monitoring.entity;

import com.parking.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class MetricSnapshot extends BaseEntity {
    private String name;
    private MetricType type;
    private Double value;
    private Map<String, Object> details = new HashMap<>();
    private Map<String, String> dimensions = new HashMap<>();
    private Long timestamp;

    public enum MetricType {
        COUNTER,
        GAUGE,
        TIMER,
        DISTRIBUTION_SUMMARY
    }
}
