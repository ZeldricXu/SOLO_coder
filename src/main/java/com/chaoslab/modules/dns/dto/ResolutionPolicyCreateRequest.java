package com.chaoslab.modules.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ResolutionPolicyCreateRequest {

    @NotBlank(message = "名称不能为空")
    private String name;

    @NotBlank(message = "域名模式不能为空")
    private String domainPattern;

    private String strategy = "round_robin";

    private List<String> upstreamIds;

    private Integer cacheTtl = 300;

    private Boolean enabled = true;
}
