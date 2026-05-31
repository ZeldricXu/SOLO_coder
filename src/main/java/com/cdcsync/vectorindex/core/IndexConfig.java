package com.cdcsync.vectorindex.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexConfig {

    private int dimension;

    private String indexType;

    private String metricType;

    @Builder.Default
    private int m = 16;

    @Builder.Default
    private int efConstruction = 100;

    @Builder.Default
    private int efSearch = 50;

    @Builder.Default
    private boolean normalize = true;
}
