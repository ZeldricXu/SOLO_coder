package com.configcenter.common.dto;

import com.configcenter.common.enums.ConfigType;
import com.configcenter.common.enums.Environment;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@Data
public class CreateConfigRequest {
    
    @NotBlank(message = "配置键不能为空")
    private String configKey;

    @NotBlank(message = "配置值不能为空")
    private String configValue;

    private ConfigType configType = ConfigType.STRING;

    private Boolean isEncrypted = false;

    @NotNull(message = "环境不能为空")
    private Environment environment;

    @NotBlank(message = "分组ID不能为空")
    private String groupId;

    private String description;

    private String operator = "system";
    
    private List<String> validationRuleIds;
    
    private Map<String, Object> validationParams;
    
    private Boolean autoPush = false;
}
