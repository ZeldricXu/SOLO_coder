package com.tsdbproxy.lineage.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class LineageGraphResult {

    private List<Node> nodes;
    private List<Edge> edges;
    private Map<String, Set<String>> tableLineage;
    private Map<String, Set<String>> columnLineage;

    @Data
    public static class Node {
        private String id;
        private String name;
        private String type;
        private String table;
        private String column;
    }

    @Data
    public static class Edge {
        private String source;
        private String target;
        private String transformType;
    }
}
