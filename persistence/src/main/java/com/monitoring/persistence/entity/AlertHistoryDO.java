package com.monitoring.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_history")
public class AlertHistoryDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String alertId;

    private String ruleId;

    private String severity;

    private String status;

    private Double currentValue;

    private String message;

    private String labels;

    private String annotations;

    private Instant startedAt;

    private Instant resolvedAt;

    private Instant createdAt;
}
