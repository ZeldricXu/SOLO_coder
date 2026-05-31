package com.tsdbproxy.query.stream.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PhysicalPlan {
    private String operator;
    private String executionMode;
    private String storageEngine;
    private List<String> partitions;
    private List<PhysicalPlan> children;
}
