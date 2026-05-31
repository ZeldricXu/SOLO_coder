package com.chaoslab.modules.image.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RepositoryCreateRequest {

    @NotBlank(message = "名称不能为空")
    private String name;

    @NotBlank(message = "Registry地址不能为空")
    private String registryUrl;

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    private String authType = "none";

    private String username;

    private String password;

    private Boolean tlsVerify = true;
}
