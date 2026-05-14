package com.mobilestore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AppCreateRequest {

    @NotBlank(message = "应用名称不能为空")
    private String name;

    @NotBlank(message = "平台不能为空")
    private String platform;

    @NotBlank(message = "分类不能为空")
    private String category;

    private String icon;
    private String description;
    private String developerId;
}
