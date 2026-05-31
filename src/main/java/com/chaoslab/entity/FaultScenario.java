package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fault_scenario")
public class FaultScenario extends BaseEntity {

    private String scenarioId;
    private String name;
    private String description;
    private String faultType;
    private Map<String, Object> scope;
    private Map<String, Object> config;
    private Long durationMs;
    private Boolean autoRollback;
    private Long rollbackTimeoutMs;
    private List<String> tags;
    private Boolean enabled;
}
