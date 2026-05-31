package com.meshcontrol.image.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegistryRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "url is required")
    private String url;

    @NotBlank(message = "type is required")
    private String type;

    private String authType = "none";
    private String username;
    private String password;
    private Boolean tlsEnabled = true;
    private Boolean insecureSkipVerify = false;
    private Integer priority = 0;
    private Boolean enabled = true;
}
