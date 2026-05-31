package com.chaoslab.modules.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CommandSubmitRequest {

    @NotBlank(message = "命令类型不能为空")
    private String commandType;

    private String aggregateId;

    @NotNull(message = "命令负载不能为空")
    private Map<String, Object> payload;

    private Map<String, Object> metadata;

    private String createdBy;
}
