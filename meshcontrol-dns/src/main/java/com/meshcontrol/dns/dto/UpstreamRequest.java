package com.meshcontrol.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpstreamRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "address is required")
    private String address;

    private Integer port = 53;
    private String protocol = "udp";
    private Integer timeoutMs = 5000;
    private Integer priority = 0;
    private Boolean enabled = true;
    private Boolean healthCheckEnabled = true;
}
