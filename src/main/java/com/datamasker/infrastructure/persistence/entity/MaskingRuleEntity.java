package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_masking_rule")
public class MaskingRuleEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String fieldPattern;

    private String strategy;

    private String levelRequired;

    private String params;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
