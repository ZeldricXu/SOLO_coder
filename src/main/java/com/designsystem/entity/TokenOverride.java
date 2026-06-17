package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_token_override")
public class TokenOverride extends BaseEntity {
    private Long tokenId;
    private String overrideValue;
    private String scope;
    private String scopeType;
    private String theme;
    private String breakpoint;
    private Integer priority;
}
