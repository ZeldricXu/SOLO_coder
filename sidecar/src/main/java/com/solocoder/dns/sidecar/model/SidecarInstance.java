package com.solocoder.dns.sidecar.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class SidecarInstance implements Serializable {
    private String instanceId;
    private String serviceName;
    private String version;
    private String host;
    private Integer port;
    private String status;
    private String configHash;
    private Double cpuLimit;
    private Double memoryLimit;
    private LocalDateTime createdAt;
    private LocalDateTime heartbeatAt;
}
