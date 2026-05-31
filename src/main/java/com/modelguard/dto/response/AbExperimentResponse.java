package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbExperimentResponse {

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
    private List<String> metrics;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
