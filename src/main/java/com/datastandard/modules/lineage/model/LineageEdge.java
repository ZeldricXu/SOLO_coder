package com.datastandard.modules.lineage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageEdge {
    private String source;
    private String target;
    private String edgeType;
    private String transformType;
    private List<String> columns;
    private String jobName;
    private Instant timestamp;

    public enum EdgeType {
        TRANSFORM, JOB, SPARK_PLAN, CUSTOM
    }
}
