package com.iotplatform.storage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class ObjectUploadDTO {

    @NotBlank(message = "桶名不能为空")
    private String bucketName;

    @NotBlank(message = "对象键不能为空")
    private String objectKey;

    private String objectName;

    private String contentType;

    private Map<String, String> metadata;

    private Map<String, String> tags;

    private String provider;

    private String createdBy;

    private byte[] content;
}
