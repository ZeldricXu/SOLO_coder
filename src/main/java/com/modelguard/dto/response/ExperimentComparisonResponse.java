package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentComparisonResponse {

    private String experimentId;
    private String experimentName;
    private Long controlGroupCount;
    private Long experimentalGroupCount;
    private Map<String, Object> controlGroupMetrics;
    private Map<String, Object> experimentalGroupMetrics;
    private Map<String, Object> metricDeltas;
    private String winningGroup;
    private String confidenceLevel;
}
