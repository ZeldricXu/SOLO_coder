package com.solocoder.platform.storage.adapter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class StorageRequestDto {

    @NotBlank(message = "存储类型不能为空")
    private String storageType;

    private String network;

    private String content;

    private String contentType;

    private String filename;

    private Map<String, Object> metadata;

    private Boolean pin = true;

    private String pinLocation;

    private String createdBy;
}
