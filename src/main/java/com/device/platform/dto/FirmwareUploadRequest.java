package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FirmwareUploadRequest {
    @NotBlank(message = "productKey不能为空")
    private String productKey;

    @NotBlank(message = "version不能为空")
    private String version;

    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String md5;
    private String signature;
    private String diffFromVersion;
    private String description;
    private String releaseNotes;
}
