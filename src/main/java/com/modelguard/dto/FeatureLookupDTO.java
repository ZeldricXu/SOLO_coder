package com.modelguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class FeatureLookupDTO implements Serializable {

    @NotBlank(message = "实体ID不能为空")
    private String entityId;

    @NotEmpty(message = "特征ID列表不能为空")
    private List<String> featureIds;

    private LocalDateTime asOfTime;

    private Boolean onlineOnly = true;
}
