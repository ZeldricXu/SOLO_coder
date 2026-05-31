package com.tracetopology.domain.topology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceTopology {

    private String id;
    private String namespace;
    @Builder.Default
    private Map<String, ServiceNode> nodes = new HashMap<>();
    @Builder.Default
    private List<ServiceEdge> edges = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public void addNode(ServiceNode node) {
        this.nodes.put(node.getId(), node);
        this.updatedAt = Instant.now();
    }

    public void addEdge(ServiceEdge edge) {
        this.edges.add(edge);
        this.updatedAt = Instant.now();
    }

    public ServiceNode getNode(String nodeId) {
        return this.nodes.get(nodeId);
    }

    public List<ServiceNode> getActiveNodes() {
        return this.nodes.values().stream()
                .filter(ServiceNode::isActive)
                .toList();
    }
}
