package com.datamigrate.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequest {

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @NotNull(message = "源数据源配置不能为空")
    private SourceConfig sourceConfig;

    @NotNull(message = "目标数据源配置不能为空")
    private TargetConfig targetConfig;

    private List<MappingRuleDto> mappingRules;

    private String primaryKeyField;

    private Integer batchSize;

    private Integer maxRetryTimes;

    private Boolean autoVerify;

    private String description;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceConfig {
        @NotBlank(message = "源数据库类型不能为空")
        private String sourceType;
        
        private String host;
        private Integer port;
        private String database;
        private String username;
        private String password;
        private String table;
        private String query;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetConfig {
        @NotBlank(message = "目标数据库类型不能为空")
        private String targetType;
        
        private String host;
        private Integer port;
        private String database;
        private String username;
        private String password;
        private String table;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingRuleDto {
        @NotBlank(message = "源字段不能为空")
        private String sourceField;
        
        @NotBlank(message = "目标字段不能为空")
        private String targetField;
        
        private String transformation;
        private Integer ruleOrder;
    }
}
