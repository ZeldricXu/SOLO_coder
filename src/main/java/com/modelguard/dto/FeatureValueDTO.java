package com.modelguard.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class FeatureValueDTO implements Serializable {

    @NotBlank(message = "特征ID不能为空")
    private String featureId;

    @NotBlank(message = "实体ID不能为空")
    private String entityId;

    private String value;

    private LocalDateTime timestamp;

    private Boolean isOnline = true;
}
