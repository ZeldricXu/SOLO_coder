package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_experiment_result", autoResultMap = true)
public class AbExperimentResult extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String resultId;

    private String experimentId;

    private String userId;

    private String groupId;

    private Integer promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;

    private Long latencyMs;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scores;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
