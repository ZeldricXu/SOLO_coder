package com.taskflow.tenant.model;

import com.taskflow.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class Tenant extends BaseEntity {

    private String tenantId;
    private String name;
    private String status;
    private String planType;
    private LocalDateTime expireAt;
    private Map<String, Object> config;
    private Map<String, Object> quota;
    private String contactEmail;
    private String contactPhone;
    private String address;
}
