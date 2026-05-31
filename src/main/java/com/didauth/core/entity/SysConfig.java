package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sys_config")
public class SysConfig extends BaseEntity {

    private String configId;
    private String namespace;
    private Integer version;
    private String parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;
}
