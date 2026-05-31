package com.edgescheduler.shadow.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DeviceShadowDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    private Map<String, Object> desired;
    private Map<String, Object> reported;
    private Map<String, Object> delta;
    private Integer version;
    private LocalDateTime lastSyncAt;
    private LocalDateTime lastDesiredUpdateAt;
    private LocalDateTime lastReportedUpdateAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
