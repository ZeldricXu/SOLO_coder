package com.example.configmanager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigUpdateDTO {

    @NotNull(message = "配置参数不能为空")
    private Map<String, Object> parameters;

    private Boolean enabled;

    private String comment;
}
