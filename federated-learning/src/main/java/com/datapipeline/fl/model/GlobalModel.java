package com.datapipeline.fl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalModel {

    private String modelId;
    private String name;
    private int version;
    private Map<String, double[]> weights;
    private Map<String, Object> metadata;
    @Builder.Default
    private Instant createdAt = Instant.now();
    private Instant updatedAt;
    private int round;
    private int participantCount;
    private double accuracy;
    private double loss;

}
