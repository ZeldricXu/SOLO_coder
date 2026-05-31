package com.llmgateway.featurestore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BackfillJobCreateDTO implements Serializable {

    @NotBlank(message = "特征ID不能为空")
    private String featureId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String createdBy;
}
