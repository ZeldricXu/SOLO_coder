package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_component_prop")
public class ComponentProp extends BaseEntity {
    private Long componentVersionId;
    private String name;
    private String propType;
    private String defaultValue;
    private String description;
    private Integer required;
    private String possibleValues;
    private Integer sortOrder;
}
