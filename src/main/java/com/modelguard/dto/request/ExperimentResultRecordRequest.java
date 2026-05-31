package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class ExperimentResultRecordRequest {

    private String experimentId;

    private String userId;

    private String groupId;

    private Integer promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;

    private Long latencyMs;

    private Map<String, Object> scores;

    private Map<String, Object> metadata;
}
