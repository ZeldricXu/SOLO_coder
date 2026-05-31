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
@TableName(value = "adversarial_attack", autoResultMap = true)
public class AdversarialAttack extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String attackId;

    private String attackName;

    private String targetModel;

    private String targetVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> attackStrategies;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attackConfig;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Integer totalPrompts;

    private Integer successfulAttacks;

    private Integer failedAttacks;

    private Double successRate;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultsSummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> severityDistribution;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> categoryDistribution;

    private String initiatedBy;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
