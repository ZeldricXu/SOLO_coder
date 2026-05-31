package com.meshcontrol.image.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "image_manifest", autoResultMap = true)
public class ImageManifest extends BaseEntity {

    private String manifestId;
    private String repoId;
    private String digest;
    private String tag;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> layers;

    private Long totalSize;
    private String architecture;
    private String os;
    private Boolean p2pEnabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> p2pSeedNodes;

    private Integer pullCount;
}
