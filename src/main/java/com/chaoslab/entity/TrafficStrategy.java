package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("traffic_strategy")
public class TrafficStrategy extends BaseEntity {

    private String strategyId;
    private String name;
    private String type;
    private String namespace;
    private Map<String, Object> selector;
    private Map<String, Object> config;
    private Boolean enabled;
    private String status;
}
