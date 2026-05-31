package com.observability.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_config")
public class ConfigEntity extends BaseEntity {

    private String configId;

    private String namespace;

    private Integer version;

    private Map<String, Object> parameters;

    private Boolean enabled;

    private LocalDateTime appliedAt;

    private String source;

    private String content;
}
