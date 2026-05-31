package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sidecar_config")
public class SidecarConfig extends BaseEntity {

    private String configId;
    private String instanceId;
    private Map<String, Object> configData;
    private Integer version;
    private Boolean applied;
    private LocalDateTime appliedAt;

    @TableField("version_lock")
    private Integer versionLock;
}
