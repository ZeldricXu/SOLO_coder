package com.solocoder.dns.traffic.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TrafficStrategy implements Serializable {
    private String strategyId;
    private String strategyType;
    private String name;
    private String description;
    private Map<String, Object> rules;
    private String targetService;
    private Integer trafficPercent;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
