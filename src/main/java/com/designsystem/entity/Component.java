package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import com.designsystem.common.enums.ComponentFramework;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_component")
public class Component extends BaseEntity {
    private String name;
    private String displayName;
    private String description;
    private String category;
    private String tags;
    private ComponentFramework framework;
    private Long maintainerId;
    private String latestVersion;
    private String gitRepository;
    private String npmPackage;
    private String previewUrl;
    private String screenshotUrl;
    private String readmeContent;
    private Integer status;
    private Integer published;

    @TableField(exist = false)
    private List<ComponentVersion> versions;

    @TableField(exist = false)
    private SysUser maintainer;

    @TableField(exist = false)
    private List<String> tagList;
}
