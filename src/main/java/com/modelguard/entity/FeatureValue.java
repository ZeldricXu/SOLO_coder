package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "feature_value", autoResultMap = true)
public class FeatureValue extends BaseEntity {

    @TableField("feature_id")
    private String featureId;

    @TableField("entity_id")
    private String entityId;

    @TableField("value")
    private String value;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField("is_online")
    private Boolean isOnline;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
