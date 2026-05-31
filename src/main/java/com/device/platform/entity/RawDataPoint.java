package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("raw_data_point")
public class RawDataPoint extends BaseEntity {
    private String pointId;
    private String deviceId;
    private String metricName;
    private Double metricValue;
    private String unit;
    private Instant collectedAt;
    private String tags;
    private boolean aggregated;
}
