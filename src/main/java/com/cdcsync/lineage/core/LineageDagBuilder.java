package com.cdcsync.lineage.core;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.lineage.domain.LineageEdge;
import com.cdcsync.lineage.domain.LineageGraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class LineageDagBuilder {

    public String buildLineageJson(List<LineageRelation> relations) {
        Map<String, Object> lineageData = new ConcurrentHashMap<>();

        Set<LineageNode> allNodes = new HashSet<>();
        for (LineageRelation relation : relations) {
            allNodes.add(relation.getSource());
            allNodes.add(relation.getTarget());
        }

        List<Map<String, String>> nodes = allNodes.stream()
                .map(node -> {
                    Map<String, String> nodeMap = new ConcurrentHashMap<>();
                    nodeMap.put("id", node.getQualifiedName());
                    nodeMap.put("tableName", node.getTableName());
                    nodeMap.put("columnName", node.getColumnName());
                    return nodeMap;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> edges = relations.stream()
                .map(relation -> {
                    Map<String, Object> edgeMap = new ConcurrentHashMap<>();
                    edgeMap.put("source", relation.getSource().getQualifiedName());
                    edgeMap.put("target", relation.getTarget().getQualifiedName());
                    edgeMap.put("transformation", relation.getTransformation());
                    edgeMap.put("transformationType", relation.getTransformationType());
                    return edgeMap;
                })
                .collect(Collectors.toList());

        lineageData.put("nodes", nodes);
        lineageData.put("edges", edges);
        lineageData.put("nodeCount", nodes.size());
        lineageData.put("edgeCount", edges.size());

        return JSON.toJSONString(lineageData);
    }

    public List<LineageEdge> buildEdges(String graphId, List<LineageRelation> relations) {
        List<LineageEdge> edges = new ArrayList<>();

        for (LineageRelation relation : relations) {
            LineageEdge edge = new LineageEdge();
            edge.setGraphId(graphId);
            edge.setSourceTable(relation.getSource().getTableName());
            edge.setSourceColumn(relation.getSource().getColumnName());
            edge.setTargetTable(relation.getTarget().getTableName());
            edge.setTargetColumn(relation.getTarget().getColumnName());
            edge.setTransformation(relation.getTransformation());
            edges.add(edge);
        }

        return edges;
    }

    public LineageGraph buildGraph(String sourceType, String sourceIdentifier, String sqlText, List<LineageRelation> relations) {
        LineageGraph graph = new LineageGraph();
        graph.setSourceType(sourceType);
        graph.setSourceIdentifier(sourceIdentifier);
        graph.setSqlText(sqlText);
        graph.setLineageJson(buildLineageJson(relations));
        return graph;
    }
}
