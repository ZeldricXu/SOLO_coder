package com.mobilestore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VersionPublishRequest {

    @NotBlank(message = "应用ID不能为空")
    private String appId;

    @NotBlank(message = "版本号不能为空")
    private String versionCode;

    private String versionName;

    @NotBlank(message = "包地址不能为空")
    private String packageUrl;

    private String releaseNote;
    private String submitter;
}
