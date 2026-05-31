package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_experiment", autoResultMap = true)
public class AbExperiment extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String experimentId;

    private String name;

    private String description;

    private String promptId;

    private String controlGroupPromptId;

    private Integer controlGroupPromptVersion;

    private String experimentalGroupPromptId;

    private Integer experimentalGroupPromptVersion;

    private BigDecimal trafficSplit;

    private String status;

    private String createdBy;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> metrics;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;
}
