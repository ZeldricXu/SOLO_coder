package com.llmgateway.adversarial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("adversarial_prompt")
public class AdversarialPrompt implements Serializable {

    @TableId(value = "prompt_id", type = IdType.INPUT)
    private String promptId;

    @TableField("attack_id")
    private String attackId;

    @TableField("original_prompt")
    private String originalPrompt;

    @TableField("adversarial_prompt")
    private String adversarialPrompt;

    @TableField("target_model")
    private String targetModel;

    @TableField("expected_behavior")
    private String expectedBehavior;

    @TableField("success_criteria")
    private String successCriteria;

    @TableField(value = "generated_at", fill = FieldFill.INSERT)
    private LocalDateTime generatedAt;
}
