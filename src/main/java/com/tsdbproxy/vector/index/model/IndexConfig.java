package com.tsdbproxy.vector.index.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IndexConfig {
    private String name;
    private int dimension;
    private String metricType;
    private String indexType;
    private int M;
    private int efConstruction;
}
