package com.meshcontrol.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DnsQueryRequest {

    @NotBlank(message = "domain is required")
    private String domain;

    private String type = "A";
}
