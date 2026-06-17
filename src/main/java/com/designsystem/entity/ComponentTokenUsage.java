package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_component_token_usage")
public class ComponentTokenUsage extends BaseEntity {
    private Long componentId;
    private Long tokenId;
    private String cssProperty;
    private String usageLocation;
    private String usageContext;
}
