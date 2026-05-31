package com.contractai.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document")
public class Document extends TenantBaseEntity {

    private String docCode;

    private String docTitle;

    private String docType;

    private String fileType;

    private Long fileSize;

    private String filePath;

    private Integer version;

    private String status;

    private String contentHash;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> tags;

    private Long parentId;

    private Long createdBy;

    @TableField(exist = false)
    private DocumentContent content;

    @TableField(exist = false)
    private List<DocumentClause> clauses;
}
