package com.meshcontrol.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ZoneRequest {

    @NotBlank(message = "domain is required")
    private String domain;

    private List<String> upstreamIds;
    private String resolutionPolicy = "round_robin";
    private Integer cacheTtl = 300;
    private Boolean enabled = true;
}
