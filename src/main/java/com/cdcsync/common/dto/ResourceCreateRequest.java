package com.cdcsync.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ResourceCreateRequest {

    @NotBlank(message = "资源类型不能为空")
    private String type;

    private Map<String, Object> config;

    private Map<String, String> labels;
}
