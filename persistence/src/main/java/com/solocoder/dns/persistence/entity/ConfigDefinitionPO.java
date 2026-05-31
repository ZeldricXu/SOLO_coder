package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("config_definition")
public class ConfigDefinitionPO {
    @TableId(type = IdType.INPUT)
    private String configId;
    private String namespace;
    private Integer version;
    private String parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;
}
