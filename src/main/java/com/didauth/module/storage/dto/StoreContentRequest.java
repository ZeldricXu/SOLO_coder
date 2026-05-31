package com.didauth.module.storage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class StoreContentRequest implements Serializable {

    @NotBlank(message = "storageType不能为空")
    private String storageType;

    @NotBlank(message = "content不能为空")
    private String content;

    private String encoding = "utf-8";

    private Map<String, Object> metadata;

    private String userId;
}
