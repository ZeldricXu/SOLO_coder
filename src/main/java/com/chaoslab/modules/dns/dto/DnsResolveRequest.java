package com.chaoslab.modules.dns.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DnsResolveRequest {

    @NotBlank(message = "域名不能为空")
    private String domain;

    private String queryType = "A";

    private Boolean forceRefresh = false;
}
