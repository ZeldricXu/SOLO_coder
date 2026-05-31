package com.tsdbproxy.vector.index.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Neighbor {
    private String id;
    private double distance;
    private double similarity;
}
