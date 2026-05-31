package com.tsdbproxy.vector.index.impl;

import com.tsdbproxy.vector.index.model.Neighbor;
import com.tsdbproxy.vector.index.model.VectorDocument;
import com.tsdbproxy.vector.index.spi.NearestNeighborIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class HnswNearestNeighborIndex implements NearestNeighborIndex {

    private static final int DEFAULT_M = 16;
    private static final int DEFAULT_EF_CONSTRUCTION = 200;

    private final int M;
    private final int efConstruction;
    private final Random random;

    private Node enterPoint;
    private final Map<String, float[]> vectors;
    private final Map<String, Node> nodeMap;

    public HnswNearestNeighborIndex() {
        this(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
    }

    public HnswNearestNeighborIndex(int M, int efConstruction) {
        this.M = M;
        this.efConstruction = efConstruction;
        this.random = new Random(42);
        this.vectors = new ConcurrentHashMap<>();
        this.nodeMap = new ConcurrentHashMap<>();
    }

    @Override
    public void build(List<VectorDocument> documents) {
        log.info("开始构建HNSW索引, 向量数量: {}", documents.size());
        for (VectorDocument doc : documents) {
            add(doc);
        }
        log.info("HNSW索引构建完成");
    }

    @Override
    public void add(VectorDocument document) {
        vectors.put(document.getId(), document.getVector());

        Node newNode = new Node(document.getId(), document.getVector());
        nodeMap.put(document.getId(), newNode);

        if (enterPoint == null) {
            enterPoint = newNode;
            return;
        }

        int maxLevel = enterPoint.level;
        int newLevel = (int) (-Math.log(random.nextDouble()) * (1.0 / Math.log(M)));
        newNode.level = newLevel;

        Node curr = enterPoint;
        for (int level = maxLevel; level > newLevel; level--) {
            curr = searchLayer(document.getVector(), curr, level).get(0);
        }

        for (int level = Math.min(newLevel, maxLevel); level >= 0; level--) {
            List<Node> neighbors = searchLayer(document.getVector(), curr, level);
            List<Node> selected = selectNeighbors(document.getVector(), neighbors, M);

            for (Node neighbor : selected) {
                newNode.addNeighbor(level, neighbor);
                neighbor.addNeighbor(level, newNode);

                if (neighbor.getNeighbors(level).size() > M * 2) {
                    shrinkNeighbors(neighbor, level);
                }
            }

            if (!selected.isEmpty()) {
                curr = selected.get(0);
            }
        }

        if (newLevel > maxLevel) {
            enterPoint = newNode;
        }
    }

    @Override
    public void remove(String id) {
        vectors.remove(id);
        nodeMap.remove(id);
    }

    @Override
    public List<Neighbor> search(float[] query, int topK) {
        List<Neighbor> results = new ArrayList<>();

        if (enterPoint == null) {
            return results;
        }

        Node curr = enterPoint;
        for (int level = enterPoint.level; level > 0; level--) {
            curr = searchLayer(query, curr, level).get(0);
        }

        List<Node> candidates = searchLayer(query, curr, 0);
        List<Node> topCandidates = selectNeighbors(query, candidates, Math.min(topK, candidates.size()));

        for (Node node : topCandidates) {
            float distance = cosineDistance(query, node.vector);
            results.add(Neighbor.builder()
                    .id(node.id)
                    .distance(distance)
                    .similarity(1.0 - distance)
                    .build());
        }

        results.sort(Comparator.comparingDouble(Neighbor::getDistance));
        return results;
    }

    @Override
    public int size() {
        return vectors.size();
    }

    private List<Node> searchLayer(float[] query, Node entry, int level) {
        PriorityQueue<Node> candidates = new PriorityQueue<>(Comparator.comparingDouble(n -> cosineDistance(query, n.vector)));
        Set<String> visited = new HashSet<>();

        candidates.add(entry);
        visited.add(entry.id);

        List<Node> results = new ArrayList<>();

        while (!candidates.isEmpty()) {
            Node curr = candidates.poll();
            results.add(curr);

            for (Node neighbor : curr.getNeighbors(level)) {
                if (!visited.contains(neighbor.id)) {
                    visited.add(neighbor.id);
                    candidates.add(neighbor);
                }
            }
        }

        return results;
    }

    private List<Node> selectNeighbors(float[] query, List<Node> candidates, int M) {
        candidates.sort(Comparator.comparingDouble(n -> cosineDistance(query, n.vector)));
        return candidates.subList(0, Math.min(M, candidates.size()));
    }

    private void shrinkNeighbors(Node node, int level) {
        List<Node> neighbors = node.getNeighbors(level);
        neighbors.sort(Comparator.comparingDouble(n -> cosineDistance(node.vector, n.vector)));
        node.setNeighbors(level, neighbors.subList(0, M));
    }

    private float cosineDistance(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return 1.0f - (dot / (float) (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    static class Node {
        String id;
        float[] vector;
        int level;
        Map<Integer, List<Node>> neighbors;

        Node(String id, float[] vector) {
            this.id = id;
            this.vector = vector;
            this.level = 0;
            this.neighbors = new HashMap<>();
        }

        List<Node> getNeighbors(int level) {
            return neighbors.computeIfAbsent(level, k -> new ArrayList<>());
        }

        void addNeighbor(int level, Node node) {
            getNeighbors(level).add(node);
        }

        void setNeighbors(int level, List<Node> nodes) {
            neighbors.put(level, nodes);
        }
    }
}
