package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_design_token")
public class DesignToken extends BaseEntity {
    private String tokenName;
    private String displayName;
    private String description;
    private TokenType tokenType;
    private TokenLevel tokenLevel;
    private String baseValue;
    private String inheritsFrom;
    private String category;
    private String tags;
    private Integer status;
    private String deprecatedBy;
    private String deprecationReason;

    @TableField(exist = false)
    private List<TokenOverride> overrides;

    @TableField(exist = false)
    private List<ComponentTokenUsage> componentUsages;

    @TableField(exist = false)
    private DesignToken parentToken;

    @TableField(exist = false)
    private List<DesignToken> childTokens;

    @TableField(exist = false)
    private String resolvedValue;
}
