package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_component_doc")
public class ComponentDoc extends BaseEntity {
    private Long componentVersionId;
    private String title;
    private String docType;
    private String content;
    private String exampleCode;
    private String previewUrl;
    private Integer sortOrder;
    private Integer indexed;
}
