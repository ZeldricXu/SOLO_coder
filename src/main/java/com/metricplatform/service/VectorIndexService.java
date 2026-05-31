package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.metricplatform.entity.SysVectorEmbedding;
import com.metricplatform.entity.SysVectorIndex;
import com.metricplatform.mapper.SysVectorEmbeddingMapper;
import com.metricplatform.mapper.SysVectorIndexMapper;
import com.metricplatform.util.SimpleVectorUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService extends ServiceImpl<SysVectorIndexMapper, SysVectorIndex> {

    private final SysVectorEmbeddingMapper embeddingMapper;
    private final Cache<String, Object> caffeineCache;

    private final Map<String, List<float[]>> vectorCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> idCache = new ConcurrentHashMap<>();
    private final Map<String, KDNode> kdTreeCache = new ConcurrentHashMap<>();

    @Data
    @AllArgsConstructor
    public static class SearchResult {
        private String embeddingId;
        private String originalId;
        private String originalText;
        private float score;
        private Map<String, Object> metadata;
    }

    private static class KDNode {
        int axis;
        float[] point;
        String embeddingId;
        String originalId;
        String originalText;
        Map<String, Object> metadata;
        KDNode left;
        KDNode right;

        KDNode(int axis, float[] point, String embeddingId, String originalId,
               String originalText, Map<String, Object> metadata) {
            this.axis = axis;
            this.point = point;
            this.embeddingId = embeddingId;
            this.originalId = originalId;
            this.originalText = originalText;
            this.metadata = metadata;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SysVectorIndex createIndex(String indexName, String description, int dimension,
                                      String similarity, String indexType, Map<String, Object> buildConfig) {
        SysVectorIndex index = new SysVectorIndex();
        index.setIndexId("vidx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        index.setIndexName(indexName);
        index.setDescription(description);
        index.setDimension(dimension);
        index.setSimilarity(similarity != null ? similarity.toLowerCase() : "cosine");
        index.setIndexType(indexType != null ? indexType.toLowerCase() : "hnsw");
        index.setStatus("building");
        index.setBuildConfig(buildConfig);
        index.setVectorCount(0L);
        index.setStoragePath("data/vector/" + index.getIndexId());
        index.setBuiltAt(LocalDateTime.now());
        index.setLastUpdatedAt(LocalDateTime.now());

        this.save(index);

        buildIndexAsync(index.getIndexId());

        log.info("已创建向量索引: {} (维度: {}, 相似度: {})", indexName, dimension, index.getSimilarity());
        return index;
    }

    @Async("vectorExecutor")
    public void buildIndexAsync(String indexId) {
        try {
            Thread.sleep(100);
            SysVectorIndex index = this.getById(indexId);
            if (index == null) {
                return;
            }

            loadIndexIntoMemory(indexId);

            index.setStatus("ready");
            index.setLastUpdatedAt(LocalDateTime.now());
            this.updateById(index);

            log.info("向量索引构建完成: {}", index.getIndexName());
        } catch (Exception e) {
            log.error("构建向量索引失败: {}", indexId, e);
            SysVectorIndex index = this.getById(indexId);
            if (index != null) {
                index.setStatus("error");
                this.updateById(index);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SysVectorEmbedding addEmbedding(String indexId, String originalId, String text,
                                           Map<String, Object> metadata) {
        SysVectorIndex index = this.getById(indexId);
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexId);
        }

        float[] vector = SimpleVectorUtil.generateVectorFromText(text, index.getDimension());

        SysVectorEmbedding embedding = new SysVectorEmbedding();
        embedding.setEmbeddingId("emb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        embedding.setIndexId(indexId);
        embedding.setOriginalId(originalId);
        embedding.setOriginalText(text);
        embedding.setVector(vector);
        embedding.setMetadata(metadata);
        embedding.setCreatedAt(LocalDateTime.now());

        embeddingMapper.insert(embedding);

        addToIndexCache(indexId, vector, embedding);

        index.setVectorCount(index.getVectorCount() + 1);
        index.setLastUpdatedAt(LocalDateTime.now());
        this.updateById(index);

        return embedding;
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchAddEmbeddings(String indexId, List<Map<String, Object>> items) {
        SysVectorIndex index = this.getById(indexId);
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexId);
        }

        List<SysVectorEmbedding> embeddings = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();

        for (Map<String, Object> item : items) {
            String originalId = (String) item.get("originalId");
            String text = (String) item.get("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");

            float[] vector = SimpleVectorUtil.generateVectorFromText(text, index.getDimension());

            SysVectorEmbedding embedding = new SysVectorEmbedding();
            embedding.setEmbeddingId("emb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            embedding.setIndexId(indexId);
            embedding.setOriginalId(originalId);
            embedding.setOriginalText(text);
            embedding.setVector(vector);
            embedding.setMetadata(metadata);
            embedding.setCreatedAt(LocalDateTime.now());

            embeddings.add(embedding);
            vectors.add(vector);
        }

        for (SysVectorEmbedding embedding : embeddings) {
            embeddingMapper.insert(embedding);
            addToIndexCache(indexId, embedding.getVector(), embedding);
        }

        index.setVectorCount(index.getVectorCount() + embeddings.size());
        index.setLastUpdatedAt(LocalDateTime.now());
        this.updateById(index);

        log.info("批量添加向量完成: 索引={}, 数量={}", indexId, embeddings.size());
    }

    private void addToIndexCache(String indexId, float[] vector, SysVectorEmbedding embedding) {
        vectorCache.computeIfAbsent(indexId, k -> new ArrayList<>()).add(vector);
        idCache.computeIfAbsent(indexId, k -> new ArrayList<>()).add(embedding.getEmbeddingId());
        kdTreeCache.remove(indexId);
    }

    private void loadIndexIntoMemory(String indexId) {
        List<SysVectorEmbedding> embeddings = embeddingMapper.selectList(
                new LambdaQueryWrapper<SysVectorEmbedding>()
                        .eq(SysVectorEmbedding::getIndexId, indexId)
                        .last("LIMIT 100000"));

        List<float[]> vectors = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (SysVectorEmbedding emb : embeddings) {
            vectors.add(emb.getVector());
            ids.add(emb.getEmbeddingId());
        }

        vectorCache.put(indexId, vectors);
        idCache.put(indexId, ids);

        if (!vectors.isEmpty()) {
            buildKDTree(indexId, embeddings);
        }

        log.info("索引加载到内存: {}, 向量数={}", indexId, vectors.size());
    }

    private void buildKDTree(String indexId, List<SysVectorEmbedding> embeddings) {
        if (embeddings.isEmpty()) {
            return;
        }

        int dimension = embeddings.get(0).getVector().length;
        List<float[]> points = new ArrayList<>();
        List<SysVectorEmbedding> embeddingList = new ArrayList<>(embeddings);

        for (SysVectorEmbedding emb : embeddings) {
            points.add(emb.getVector());
        }

        KDNode root = buildKDTreeRecursive(points, embeddingList, 0, dimension);
        kdTreeCache.put(indexId, root);
    }

    private KDNode buildKDTreeRecursive(List<float[]> points, List<SysVectorEmbedding> embeddings,
                                        int depth, int dimension) {
        if (points.isEmpty()) {
            return null;
        }

        int axis = depth % dimension;
        int median = points.size() / 2;

        int[][] indices = new int[points.size()][2];
        for (int i = 0; i < points.size(); i++) {
            indices[i][0] = i;
            indices[i][1] = Float.floatToIntBits(points.get(i)[axis]);
        }

        Arrays.sort(indices, Comparator.comparingInt(a -> a[1]));

        List<float[]> sortedPoints = new ArrayList<>();
        List<SysVectorEmbedding> sortedEmbeddings = new ArrayList<>();
        for (int[] idx : indices) {
            sortedPoints.add(points.get(idx[0]));
            sortedEmbeddings.add(embeddings.get(idx[0]));
        }

        median = sortedPoints.size() / 2;

        SysVectorEmbedding medianEmb = sortedEmbeddings.get(median);
        KDNode node = new KDNode(axis, sortedPoints.get(median),
                medianEmb.getEmbeddingId(), medianEmb.getOriginalId(),
                medianEmb.getOriginalText(), medianEmb.getMetadata());

        node.left = buildKDTreeRecursive(
                sortedPoints.subList(0, median),
                sortedEmbeddings.subList(0, median),
                depth + 1, dimension);

        node.right = buildKDTreeRecursive(
                sortedPoints.subList(median + 1, sortedPoints.size()),
                sortedEmbeddings.subList(median + 1, sortedEmbeddings.size()),
                depth + 1, dimension);

        return node;
    }

    public List<SearchResult> search(String indexId, String queryText, int topK, String metric) {
        SysVectorIndex index = this.getById(indexId);
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexId);
        }

        if (!"ready".equals(index.getStatus())) {
            throw new IllegalStateException("索引未就绪，当前状态: " + index.getStatus());
        }

        float[] queryVector = SimpleVectorUtil.generateVectorFromText(queryText, index.getDimension());

        return searchByVector(indexId, queryVector, topK,
                metric != null ? metric.toLowerCase() : index.getSimilarity());
    }

    public List<SearchResult> searchByVector(String indexId, float[] queryVector, int topK, String metric) {
        String cacheKey = "vec_search:" + indexId + ":" + Arrays.hashCode(queryVector);
        @SuppressWarnings("unchecked")
        List<SearchResult> cached = (List<SearchResult>) caffeineCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<float[]> vectors = vectorCache.get(indexId);
        List<String> ids = idCache.get(indexId);

        if (vectors == null || vectors.isEmpty()) {
            loadIndexIntoMemory(indexId);
            vectors = vectorCache.get(indexId);
            ids = idCache.get(indexId);
            if (vectors == null || vectors.isEmpty()) {
                return Collections.emptyList();
            }
        }

        PriorityBlockingQueue<SearchResult> results = new PriorityBlockingQueue<>(topK,
                Comparator.comparingDouble(SearchResult::getScore));

        Map<String, SysVectorEmbedding> embeddingMap = new HashMap<>();
        List<SysVectorEmbedding> embeddings = embeddingMapper.selectBatchIds(ids);
        for (SysVectorEmbedding emb : embeddings) {
            embeddingMap.put(emb.getEmbeddingId(), emb);
        }

        for (int i = 0; i < vectors.size(); i++) {
            float[] vec = vectors.get(i);
            float score = calculateSimilarity(queryVector, vec, metric);

            SysVectorEmbedding emb = embeddingMap.get(ids.get(i));
            if (emb != null) {
                SearchResult result = new SearchResult(
                        emb.getEmbeddingId(),
                        emb.getOriginalId(),
                        emb.getOriginalText(),
                        score,
                        emb.getMetadata()
                );

                if (results.size() < topK) {
                    results.offer(result);
                } else if (results.peek() != null && score > results.peek().getScore()) {
                    results.poll();
                    results.offer(result);
                }
            }
        }

        List<SearchResult> sortedResults = new ArrayList<>(results);
        sortedResults.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

        caffeineCache.put(cacheKey, sortedResults);

        return sortedResults;
    }

    public List<SearchResult> searchByKDTree(String indexId, float[] queryVector, int topK, String metric) {
        KDNode root = kdTreeCache.get(indexId);
        if (root == null) {
            return searchByVector(indexId, queryVector, topK, metric);
        }

        PriorityQueue<KDNode> nearest = new PriorityQueue<>(
                (a, b) -> Float.compare(
                        calculateSimilarity(queryVector, b.point, metric),
                        calculateSimilarity(queryVector, a.point, metric)
                )
        );

        searchKDTree(root, queryVector, nearest, topK, metric);

        List<SearchResult> results = new ArrayList<>();
        while (!nearest.isEmpty() && results.size() < topK) {
            KDNode node = nearest.poll();
            float score = calculateSimilarity(queryVector, node.point, metric);
            results.add(new SearchResult(
                    node.embeddingId,
                    node.originalId,
                    node.originalText,
                    score,
                    node.metadata
            ));
        }

        return results;
    }

    private void searchKDTree(KDNode node, float[] query, PriorityQueue<KDNode> nearest,
                              int topK, String metric) {
        if (node == null) {
            return;
        }

        float distance = calculateSimilarity(query, node.point, metric);

        if (nearest.size() < topK) {
            nearest.offer(node);
        } else if (nearest.peek() != null && distance > calculateSimilarity(query, nearest.peek().point, metric)) {
            nearest.poll();
            nearest.offer(node);
        }

        int axis = node.axis;
        boolean goLeftFirst = query[axis] < node.point[axis];

        KDNode first = goLeftFirst ? node.left : node.right;
        KDNode second = goLeftFirst ? node.right : node.left;

        searchKDTree(first, query, nearest, topK, metric);

        float planeDistance = Math.abs(query[axis] - node.point[axis]);
        if (nearest.size() < topK || planeDistance < nearest.peek() != null ?
                calculateSimilarity(query, nearest.peek().point, metric) : 0) {
            searchKDTree(second, query, nearest, topK, metric);
        }
    }

    private float calculateSimilarity(float[] v1, float[] v2, String metric) {
        return switch (metric) {
            case "cosine" -> SimpleVectorUtil.cosineSimilarity(v1, v2);
            case "inner_product", "ip" -> SimpleVectorUtil.innerProduct(v1, v2);
            case "euclidean", "l2" -> 1.0f / (1.0f + SimpleVectorUtil.euclideanDistance(v1, v2));
            default -> SimpleVectorUtil.cosineSimilarity(v1, v2);
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteIndex(String indexId) {
        embeddingMapper.delete(new LambdaQueryWrapper<SysVectorEmbedding>()
                .eq(SysVectorEmbedding::getIndexId, indexId));
        vectorCache.remove(indexId);
        idCache.remove(indexId);
        kdTreeCache.remove(indexId);
        return this.removeById(indexId);
    }

    public List<SysVectorIndex> getAllIndexes() {
        return this.list();
    }

    public SysVectorIndex getIndexById(String indexId) {
        return this.getById(indexId);
    }

    public long getEmbeddingCount(String indexId) {
        return embeddingMapper.selectCount(new LambdaQueryWrapper<SysVectorEmbedding>()
                .eq(SysVectorEmbedding::getIndexId, indexId));
    }

    public List<SysVectorEmbedding> getEmbeddings(String indexId, int offset, int limit) {
        return embeddingMapper.selectList(new LambdaQueryWrapper<SysVectorEmbedding>()
                .eq(SysVectorEmbedding::getIndexId, indexId)
                .orderByDesc(SysVectorEmbedding::getCreatedAt)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteEmbedding(String embeddingId) {
        SysVectorEmbedding embedding = embeddingMapper.selectById(embeddingId);
        if (embedding == null) {
            return false;
        }

        embeddingMapper.deleteById(embeddingId);

        List<float[]> vectors = vectorCache.get(embedding.getIndexId());
        List<String> ids = idCache.get(embedding.getIndexId());
        if (vectors != null && ids != null) {
            int idx = ids.indexOf(embeddingId);
            if (idx >= 0) {
                vectors.remove(idx);
                ids.remove(idx);
                kdTreeCache.remove(embedding.getIndexId());
            }
        }

        return true;
    }
}
