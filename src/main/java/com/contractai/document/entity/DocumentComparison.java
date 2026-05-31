package com.contractai.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_comparison")
public class DocumentComparison extends TenantBaseEntity {

    private String comparisonCode;

    private String comparisonName;

    private Long sourceDocId;

    private Long targetDocId;

    private String comparisonType;

    private String status;

    private String algorithm;

    private BigDecimal similarityScore;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> diffStats;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> highlights;

    private String changeSummary;

    private String detailedDiffs;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorDetail;

    private Long createdBy;

    @TableField(exist = false)
    private Document sourceDocument;

    @TableField(exist = false)
    private Document targetDocument;
}
