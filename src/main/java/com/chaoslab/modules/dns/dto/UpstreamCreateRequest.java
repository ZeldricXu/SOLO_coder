package com.chaoslab.modules.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpstreamCreateRequest {

    @NotBlank(message = "名称不能为空")
    private String name;

    @NotBlank(message = "地址不能为空")
    private String address;

    private String protocol = "udp";

    private Integer timeoutMs = 5000;

    private Integer priority = 100;

    private Boolean healthCheckEnabled = true;
}
