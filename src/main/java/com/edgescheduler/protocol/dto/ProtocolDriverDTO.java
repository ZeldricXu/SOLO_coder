package com.edgescheduler.protocol.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ProtocolDriverDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String driverId;

    @NotEmpty(message = "driverName cannot be empty")
    private String driverName;

    @NotEmpty(message = "protocolType cannot be empty")
    private String protocolType;

    private String driverVersion;
    private String status;
    private Map<String, Object> configSchema;
    private Map<String, Object> dataMapping;
    private String jarPath;
    private String className;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
