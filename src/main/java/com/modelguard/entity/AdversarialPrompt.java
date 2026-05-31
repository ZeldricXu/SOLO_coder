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
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "adversarial_prompt", autoResultMap = true)
public class AdversarialPrompt extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String promptId;

    private String attackType;

    private String attackStrategy;

    private String originalPrompt;

    private String adversarialPrompt;

    private String targetModel;

    private String targetVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attackParameters;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelResponse;

    private String attackSuccess;

    private Double confidenceScore;

    private String severity;

    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extractedData;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private String generatedBy;

    private LocalDateTime generatedAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
