package com.configcenter.common.dto;

import com.configcenter.common.enums.Environment;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class CreateGroupRequest {
    
    @NotBlank(message = "分组名称不能为空")
    private String groupName;

    @NotNull(message = "环境不能为空")
    private Environment environment;

    private String description;

    private List<String> applications;

    private String operator = "system";
    
    private Integer parallelPushCount;
    
    private String groupType;
}
