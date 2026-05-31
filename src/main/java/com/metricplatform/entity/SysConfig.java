package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String configId;

    private String namespace;

    private Integer version;

    private Map<String, Object> parameters;

    private Map<String, Object> defaultValues;

    private Map<String, Object> validationRules;

    private Boolean enabled;

    private LocalDateTime appliedAt;
}
