package com.datastandard.modules.lineage.storage;

import com.datastandard.modules.lineage.model.LineageEdge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InMemoryLineageStorage implements LineageStorage {

    private final Map<String, List<LineageEdge>> upstreamIndex = new ConcurrentHashMap<>();
    private final Map<String, List<LineageEdge>> downstreamIndex = new ConcurrentHashMap<>();
    private final List<LineageEdge> allEdges = new ArrayList<>();

    @Override
    public void saveEdge(LineageEdge edge) {
        log.info("保存血缘边: {} -> {}", edge.getSource(), edge.getTarget());
        synchronized (allEdges) {
            allEdges.add(edge);
            upstreamIndex.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge);
            downstreamIndex.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
            saveToDatabase(edge);
        }
    }

    @Override
    public void saveEdges(List<LineageEdge> edges) {
        for (LineageEdge edge : edges) {
            saveEdge(edge);
        }
    }

    @Override
    public List<LineageEdge> queryUpstream(String tableName, int depth) {
        log.info("查询上游血缘: {}, depth={}", tableName, depth);
        List<LineageEdge> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(tableName);
        int currentDepth = 0;

        while (!queue.isEmpty() && currentDepth < depth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                if (visited.contains(current)) continue;
                visited.add(current);

                List<LineageEdge> edges = upstreamIndex.getOrDefault(current, Collections.emptyList());
                for (LineageEdge edge : edges) {
                    result.add(edge);
                    if (!visited.contains(edge.getSource())) {
                        queue.add(edge.getSource());
                    }
                }
            }
            currentDepth++;
        }

        return result;
    }

    @Override
    public List<LineageEdge> queryDownstream(String tableName, int depth) {
        log.info("查询下游血缘: {}, depth={}", tableName, depth);
        List<LineageEdge> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(tableName);
        int currentDepth = 0;

        while (!queue.isEmpty() && currentDepth < depth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                if (visited.contains(current)) continue;
                visited.add(current);

                List<LineageEdge> edges = downstreamIndex.getOrDefault(current, Collections.emptyList());
                for (LineageEdge edge : edges) {
                    result.add(edge);
                    if (!visited.contains(edge.getTarget())) {
                        queue.add(edge.getTarget());
                    }
                }
            }
            currentDepth++;
        }

        return result;
    }

    @Override
    public List<LineageEdge> getAllEdges() {
        synchronized (allEdges) {
            return new ArrayList<>(allEdges);
        }
    }

    @Override
    public void clear() {
        log.info("清空血缘存储");
        synchronized (allEdges) {
            upstreamIndex.clear();
            downstreamIndex.clear();
            allEdges.clear();
        }
    }

    private void saveToDatabase(LineageEdge edge) {
        log.debug("保存到数据库: {}", edge);
    }
}
