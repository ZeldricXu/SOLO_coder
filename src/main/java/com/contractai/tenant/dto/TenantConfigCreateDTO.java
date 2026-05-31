package com.contractai.tenant.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TenantConfigCreateDTO {

    private String configId;
    private String namespace;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;
    private String description;
}
