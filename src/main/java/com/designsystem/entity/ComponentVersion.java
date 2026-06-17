package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_component_version")
public class ComponentVersion extends BaseEntity {
    private Long componentId;
    private String version;
    private String changelog;
    private String releaseNotes;
    private String sourceCodePath;
    private String compiledCodePath;
    private String previewHtmlPath;
    private String commitHash;
    private Integer isLatest;
    private Integer isPrerelease;
    private String deprecatedReason;

    @TableField(exist = false)
    private Component component;

    @TableField(exist = false)
    private List<ComponentDoc> docs;

    @TableField(exist = false)
    private List<ComponentProp> props;
}
