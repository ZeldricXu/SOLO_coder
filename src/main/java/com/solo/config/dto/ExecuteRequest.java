package com.solo.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ExecuteRequest {

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    @NotNull(message = "参数不能为空")
    private Map<String, Object> params;

    private Map<String, Object> payload;
}
