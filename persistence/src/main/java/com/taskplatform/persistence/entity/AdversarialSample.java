package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adversarial_samples")
public class AdversarialSample extends BaseEntity {

    @TableField("sample_id")
    private String sampleId;

    @TableField("attack_type")
    private String attackType;

    @TableField("original_prompt")
    private String originalPrompt;

    @TableField("adversarial_prompt")
    private String adversarialPrompt;

    @TableField("target_model")
    private String targetModel;

    @TableField("attack_strategy")
    private String attackStrategy;

    @TableField("success")
    private Boolean success;

    @TableField("confidence_score")
    private Double confidenceScore;

    @TableField("evaluation_result")
    private String evaluationResult;

    @TableField("model_response")
    private String modelResponse;

    @TableField("metadata")
    private String metadata;

    @TableField("created_by")
    private String createdBy;
}
