package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config")
public class Config extends BaseEntity {

    private String configId;

    private String namespace;

    private Integer version;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private Boolean enabled;

    private LocalDateTime appliedAt;
}
