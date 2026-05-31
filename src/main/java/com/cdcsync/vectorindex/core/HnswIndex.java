package com.cdcsync.vectorindex.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class HnswIndex implements AutoCloseable {

    @Getter
    private final IndexConfig config;

    @Getter
    private final int dimension;

    private final ConcurrentHashMap<Long, float[]> vectors;
    private final ConcurrentHashMap<Long, List<List<Long>>> connections;
    private final ReentrantReadWriteLock lock;

    private long maxLevel;
    private long entryPoint;
    private long nextId;
    private final Random random;
    private volatile boolean closed = false;

    public HnswIndex(IndexConfig config) {
        this.config = config;
        this.dimension = config.getDimension();
        this.vectors = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.maxLevel = 0;
        this.entryPoint = -1;
        this.nextId = 0;
        this.random = new Random(42);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        lock.writeLock().lock();
        try {
            closed = true;
            vectors.clear();
            connections.clear();
            entryPoint = -1;
            maxLevel = 0;
            nextId = 0;
            log.info("HnswIndex closed successfully, dimension={}", dimension);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("HnswIndex is closed");
        }
    }

    private long randomLevel() {
        double level = -Math.log(random.nextDouble()) * (1.0 / Math.log(config.getM()));
        return Math.min((long) level, 32);
    }

    public long add(float[] vector) {
        ensureOpen();
        VectorUtils.validateDimension(vector, dimension);

        float[] vec = config.isNormalize() ? VectorUtils.normalize(vector) : vector.clone();
        long id = nextId++;

        lock.writeLock().lock();
        try {
            vectors.put(id, vec);
            connections.put(id, new ArrayList<>());

            long newLevel = randomLevel();

            if (entryPoint == -1) {
                entryPoint = id;
                maxLevel = newLevel;
                for (int i = 0; i <= newLevel; i++) {
                    connections.get(id).add(new ArrayList<>());
                }
                return id;
            }

            long currentNode = entryPoint;
            long currentLevel = maxLevel;

            for (long l = currentLevel; l > newLevel; l--) {
                currentNode = searchLayer(vec, currentNode, l).get(0);
            }

            for (long l = Math.min(newLevel, maxLevel); l >= 0; l--) {
                List<Long> candidates = searchLayer(vec, currentNode, l);
                List<Long> neighbors = selectNeighbors(vec, candidates, config.getM());
                connections.get(id).add(0, new ArrayList<>(neighbors));

                for (long neighbor : neighbors) {
                    List<Long> neighborConn = connections.get(neighbor);
                    if (neighborConn.size() <= l) {
                        while (neighborConn.size() <= l) {
                            neighborConn.add(new ArrayList<>());
                        }
                    }
                    neighborConn.get((int) l).add(id);
                    if (neighborConn.get((int) l).size() > config.getM() * 2) {
                        List<Long> newNeighbors = selectNeighbors(
                            vectors.get(neighbor), neighborConn.get((int) l), config.getM()
                        );
                        neighborConn.set((int) l, newNeighbors);
                    }
                }

                if (!neighbors.isEmpty()) {
                    currentNode = neighbors.get(0);
                }
            }

            if (newLevel > maxLevel) {
                for (long l = maxLevel + 1; l <= newLevel; l++) {
                    connections.get(id).add(new ArrayList<>());
                }
                entryPoint = id;
                maxLevel = newLevel;
            }

            return id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Long> addBatch(List<float[]> vectors) {
        ensureOpen();
        List<Long> ids = new ArrayList<>();
        for (float[] vec : vectors) {
            ids.add(add(vec));
        }
        return ids;
    }

    public List<Map.Entry<Long, Float>> search(float[] queryVector, int topK) {
        ensureOpen();
        VectorUtils.validateDimension(queryVector, dimension);

        float[] query = config.isNormalize() ? VectorUtils.normalize(queryVector) : queryVector.clone();

        lock.readLock().lock();
        try {
            if (entryPoint == -1) {
                return Collections.emptyList();
            }

            long currentNode = entryPoint;
            for (long l = maxLevel; l > 0; l--) {
                currentNode = searchLayer(query, currentNode, l).get(0);
            }

            List<Long> candidates = searchLayer(query, currentNode, 0);
            List<Map.Entry<Long, Float>> results = new ArrayList<>();

            for (long candidate : candidates) {
                float dist = VectorUtils.distance(query, vectors.get(candidate), config.getMetricType());
                results.add(new AbstractMap.SimpleEntry<>(candidate, dist));
            }

            results.sort(Comparator.comparingDouble(Map.Entry::getValue));

            return results.subList(0, Math.min(topK, results.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void delete(long id) {
        ensureOpen();
        lock.writeLock().lock();
        try {
            if (!vectors.containsKey(id)) {
                return;
            }

            vectors.remove(id);

            for (int level = 0; level < connections.get(id).size(); level++) {
                for (long neighbor : connections.get(id).get(level)) {
                    if (connections.containsKey(neighbor) && connections.get(neighbor).size() > level) {
                        connections.get(neighbor).get(level).remove(id);
                    }
                }
            }

            connections.remove(id);

            if (id == entryPoint) {
                if (vectors.isEmpty()) {
                    entryPoint = -1;
                    maxLevel = 0;
                } else {
                    entryPoint = vectors.keys().nextElement();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<Long> searchLayer(float[] query, long entryNode, long level) {
        PriorityQueue<Map.Entry<Long, Float>> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(Map.Entry::getValue)
        );
        Set<Long> visited = ConcurrentHashMap.newKeySet();

        float dist = VectorUtils.distance(query, vectors.get(entryNode), config.getMetricType());
        candidates.add(new AbstractMap.SimpleEntry<>(entryNode, dist));
        visited.add(entryNode);

        List<Long> result = new ArrayList<>();
        while (!candidates.isEmpty()) {
            Map.Entry<Long, Float> current = candidates.poll();
            result.add(current.getKey());

            if (result.size() >= config.getEfSearch()) {
                break;
            }

            long currentId = current.getKey();
            List<Long> neighbors = connections.get(currentId).get((int) level);
            for (long neighbor : neighbors) {
                if (visited.add(neighbor)) {
                    float neighborDist = VectorUtils.distance(query, vectors.get(neighbor), config.getMetricType());
                    candidates.add(new AbstractMap.SimpleEntry<>(neighbor, neighborDist));
                }
            }
        }

        return result;
    }

    private List<Long> selectNeighbors(float[] query, List<Long> candidates, int k) {
        List<Map.Entry<Long, Float>> scored = new ArrayList<>();
        for (long candidate : candidates) {
            float dist = VectorUtils.distance(query, vectors.get(candidate), config.getMetricType());
            scored.add(new AbstractMap.SimpleEntry<>(candidate, dist));
        }
        scored.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    public int size() {
        ensureOpen();
        return vectors.size();
    }

    public boolean contains(long id) {
        ensureOpen();
        return vectors.containsKey(id);
    }
}
