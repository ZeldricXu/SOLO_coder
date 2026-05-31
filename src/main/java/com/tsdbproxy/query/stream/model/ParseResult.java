package com.tsdbproxy.query.stream.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ParseResult {
    private String sql;
    private LogicalPlan logicalPlan;
    private PhysicalPlan physicalPlan;
    private List<String> optimizationRules;
    private Long executionTimeMs;
    private Long parseTimeMs;
    private Long optimizeTimeMs;
    private Long translateTimeMs;
}
