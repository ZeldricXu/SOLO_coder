package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "security_assessment", autoResultMap = true)
public class SecurityAssessment extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String assessmentId;

    private String modelId;

    private String version;

    private String assessmentType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> securityScores;

    private Double overallSecurityScore;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> vulnerabilitySummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attackResults;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> recommendations;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> relatedAttackIds;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String assessedBy;

    private String riskLevel;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
