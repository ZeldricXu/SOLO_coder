package com.tsdbproxy.vector.index.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VectorDocument {
    private String id;
    private float[] vector;
    private String metadata;
}
