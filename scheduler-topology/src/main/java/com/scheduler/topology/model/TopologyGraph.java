package com.scheduler.topology.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopologyGraph {
    private List<ServiceNode> nodes;
    private List<ServiceEdge> edges;
    private Map<String, Object> summary;
    private long timestamp;
}
