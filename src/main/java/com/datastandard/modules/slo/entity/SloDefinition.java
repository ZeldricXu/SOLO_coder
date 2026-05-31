package com.datastandard.modules.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("slo_definitions")
public class SloDefinition {

    @TableId(type = IdType.ASSIGN_UUID)
    private String sloId;

    private String sloName;

    private String sloDescription;

    private String serviceName;

    private String environment;

    private String sliType;

    private Double targetValue;

    private String targetDirection;

    private Long timeWindowSeconds;

    private String alertThresholds;

    private String labels;

    private String createdBy;

    private boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;

    private Integer deleted;

    public Duration getTimeWindow() {
        return timeWindowSeconds != null ? Duration.ofSeconds(timeWindowSeconds) : null;
    }

    public void setTimeWindow(Duration duration) {
        this.timeWindowSeconds = duration != null ? duration.getSeconds() : null;
    }
}
