package com.meshcontrol.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CaBundleRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String rootCertId;
    private List<String> intermediateCertIds;
    private Integer rotationDays = 365;
    private Boolean enabled = true;
}
