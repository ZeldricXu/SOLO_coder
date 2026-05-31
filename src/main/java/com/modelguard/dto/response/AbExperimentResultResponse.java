package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbExperimentResultResponse {

    private Long id;
    private String resultId;
    private String experimentId;
    private String userId;
    private String groupId;
    private Integer promptVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long latencyMs;
    private Map<String, Object> scores;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
