package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_masking_rule")
public class DataMaskingRule extends BaseEntity {

    private String ruleId;

    private String fieldName;

    private String maskType;

    private String requiredRole;

    private String pattern;

    private String replacement;

    private Boolean enabled;
}
