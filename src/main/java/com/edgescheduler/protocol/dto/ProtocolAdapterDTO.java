package com.edgescheduler.protocol.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ProtocolAdapterDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String adapterId;

    @NotEmpty(message = "adapterName cannot be empty")
    private String adapterName;

    @NotEmpty(message = "driverId cannot be empty")
    private String driverId;

    private String deviceKey;
    private String status;
    private Map<String, Object> connectionParams;
    private Map<String, Object> adapterConfig;
    private LocalDateTime lastConnectedAt;
    private LocalDateTime lastDisconnectedAt;
    private Long totalMessages;
    private Long errorMessages;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
