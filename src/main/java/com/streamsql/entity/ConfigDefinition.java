package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_definition")
public class ConfigDefinition extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String configId;

    private String namespace;

    private Integer version;

    private String parameters;

    private Boolean enabled;

    private LocalDateTime appliedAt;
}
