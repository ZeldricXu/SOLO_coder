package com.contractai.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_clause")
public class DocumentClause extends TenantBaseEntity {

    private Long documentId;

    private String clauseCode;

    private String clauseTitle;

    private String clauseType;

    private String clauseContent;

    private Integer startPosition;

    private Integer endPosition;

    private Integer importance;

    private String riskLevel;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(exist = false)
    private String diffStatus;

    @TableField(exist = false)
    private List<Map<String, Object>> diffDetails;
}
