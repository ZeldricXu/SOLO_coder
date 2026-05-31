package com.datastandard.modules.streaming.ast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalPlan {
    private ExecutionEngine engine;
    private String operation;
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();
    private PhysicalPlan child;

    public enum ExecutionEngine {
        FLINK, SPARK_STRUCTURED_STREAMING, KAFKA_STREAMS
    }
}
