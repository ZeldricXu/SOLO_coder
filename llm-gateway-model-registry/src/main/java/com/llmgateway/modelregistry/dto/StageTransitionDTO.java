package com.llmgateway.modelregistry.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;

@Data
public class StageTransitionDTO implements Serializable {

    @NotBlank(message = "版本ID不能为空")
    private String versionId;

    @NotBlank(message = "目标阶段不能为空")
    private String toStage;

    private String reason;
    private String operatedBy;
}
