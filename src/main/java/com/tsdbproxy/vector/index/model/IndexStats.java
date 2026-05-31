package com.tsdbproxy.vector.index.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IndexStats {
    private Long indexId;
    private String name;
    private int totalVectors;
    private String status;
    private LocalDateTime lastBuildTime;
}
