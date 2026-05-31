package com.meshcontrol.dns.entity;

import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class DnsUpstream extends BaseEntity {

    private String upstreamId;
    private String name;
    private String address;
    private Integer port;
    private String protocol;
    private Integer timeoutMs;
    private Integer priority;
    private Boolean enabled;
    private Boolean healthCheckEnabled;
    private LocalDateTime lastHealthCheck;
    private String healthStatus;
}
