package com.configcenter.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@Data
public class UpdateConfigRequest {
    
    @NotBlank(message = "配置ID不能为空")
    private String configId;

    @NotBlank(message = "配置值不能为空")
    private String configValue;

    private String changeReason;

    private Boolean autoPush = true;

    private String operator = "system";
    
    private List<String> validationRuleIds;
    
    private Map<String, Object> validationParams;
    
    private Boolean asyncPush = true;
}
