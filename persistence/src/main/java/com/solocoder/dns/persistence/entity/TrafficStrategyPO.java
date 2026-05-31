package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("traffic_strategy")
public class TrafficStrategyPO {
    @TableId(type = IdType.INPUT)
    private String strategyId;
    private String strategyType;
    private String name;
    private String description;
    private String rules;
    private String targetService;
    private Integer trafficPercent;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
