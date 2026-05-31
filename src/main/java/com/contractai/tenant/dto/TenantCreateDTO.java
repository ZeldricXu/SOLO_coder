package com.contractai.tenant.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TenantCreateDTO {

    private String tenantCode;
    private String tenantName;
    private String type;
    private String industry;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private LocalDateTime expireAt;
    private Map<String, Object> attributes;
}
