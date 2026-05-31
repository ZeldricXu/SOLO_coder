package com.example.configmanager.dto;

import jakarta.validation.constraints.NotBlank;
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
public class ConfigCreateDTO {

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    @NotNull(message = "配置参数不能为空")
    private Map<String, Object> parameters;

    private Boolean enabled;

    private String comment;
}
