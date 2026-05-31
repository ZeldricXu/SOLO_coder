package com.streamsql.modules.vector_index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.VectorIndexDTO;
import com.streamsql.dto.VectorSearchDTO;
import com.streamsql.entity.VectorEmbedding;
import com.streamsql.entity.VectorIndex;
import com.streamsql.mapper.VectorEmbeddingMapper;
import com.streamsql.mapper.VectorIndexMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final VectorIndexMapper vectorIndexMapper;
    private final VectorEmbeddingMapper vectorEmbeddingMapper;
    private final ObjectMapper objectMapper;

    @Value("${streamsql.vector.index-path:./data/vector-index}")
    private String indexBasePath;

    @Value("${streamsql.vector.dimension:1536}")
    private int defaultDimension;

    private final Map<String, HnswIndex> indexCache = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public VectorIndex createIndex(VectorIndexDTO dto) throws JsonProcessingException {
        VectorIndex index = new VectorIndex();
        index.setIndexName(dto.getIndexName());
        index.setDatasourceId(dto.getDatasourceId());
        index.setTableName(dto.getTableName());
        index.setColumnName(dto.getColumnName());
        index.setVectorDimension(dto.getVectorDimension());
        index.setIndexType(dto.getIndexType());
        index.setIndexParams(objectMapper.writeValueAsString(dto.getIndexParams()));
        index.setStatus("building");
        index.setIndexPath(indexBasePath + "/" + UUID.randomUUID());

        vectorIndexMapper.insert(index);

        buildIndexAsync(index.getIndexId());

        return index;
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void buildIndexAsync(String indexId) {
        try {
            VectorIndex index = vectorIndexMapper.selectById(indexId);
            if (index == null) {
                return;
            }

            log.info("Starting to build vector index: {}", index.getIndexName());

            Path indexPath = Paths.get(index.getIndexPath());
            Files.createDirectories(indexPath.getParent());

            HnswIndex hnswIndex = new HnswIndex(index.getVectorDimension(), 16, 100);
            indexCache.put(indexId, hnswIndex);

            List<VectorEmbedding> embeddings = vectorEmbeddingMapper.selectList(
                new LambdaQueryWrapper<VectorEmbedding>()
                    .eq(VectorEmbedding::getIndexId, indexId)
            );

            for (VectorEmbedding embedding : embeddings) {
                float[] vector = bytesToFloatArray(embedding.getVector());
                hnswIndex.addPoint(embedding.getDataKey(), vector);
            }

            saveIndexToDisk(indexId, hnswIndex);

            index.setStatus("ready");
            index.setLastBuildTime(LocalDateTime.now());
            vectorIndexMapper.updateById(index);

            log.info("Vector index build completed: {}", index.getIndexName());
        } catch (Exception e) {
            log.error("Failed to build vector index: {}", indexId, e);
            VectorIndex index = vectorIndexMapper.selectById(indexId);
            if (index != null) {
                index.setStatus("failed");
                vectorIndexMapper.updateById(index);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteIndex(String indexId) {
        VectorIndex index = vectorIndexMapper.selectById(indexId);
        if (index != null) {
            indexCache.remove(indexId);
            try {
                Files.deleteIfExists(Paths.get(index.getIndexPath()));
            } catch (IOException e) {
                log.warn("Failed to delete index file: {}", index.getIndexPath(), e);
            }
        }
        vectorIndexMapper.deleteById(indexId);
    }

    public VectorIndex getIndex(String indexId) {
        return vectorIndexMapper.selectById(indexId);
    }

    public PageResult<VectorIndex> listIndexes(int page, int size, String datasourceId, String status) {
        LambdaQueryWrapper<VectorIndex> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(VectorIndex::getDatasourceId, datasourceId);
        }
        if (status != null) {
            wrapper.eq(VectorIndex::getStatus, status);
        }
        wrapper.orderByDesc(VectorIndex::getCreatedAt);

        IPage<VectorIndex> pageResult = vectorIndexMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public void addEmbedding(String indexId, String dataKey, float[] vector, Map<String, Object> metadata) throws JsonProcessingException {
        VectorEmbedding embedding = new VectorEmbedding();
        embedding.setIndexId(indexId);
        embedding.setDataKey(dataKey);
        embedding.setVector(floatArrayToBytes(vector));
        embedding.setMetadata(objectMapper.writeValueAsString(metadata));

        vectorEmbeddingMapper.insert(embedding);

        HnswIndex index = getOrLoadIndex(indexId);
        if (index != null) {
            index.addPoint(dataKey, vector);
        }
    }

    public List<Map<String, Object>> search(String indexId, VectorSearchDTO dto) {
        HnswIndex index = getOrLoadIndex(indexId);
        if (index == null) {
            throw new IllegalArgumentException("索引不存在或未就绪: " + indexId);
        }

        float[] queryVector = new float[dto.getVector().size()];
        for (int i = 0; i < dto.getVector().size(); i++) {
            queryVector[i] = dto.getVector(i);
        }

        List<HnswIndex.SearchResult> results = index.search(queryVector, dto.getTopK());

        List<Map<String, Object>> searchResults = new ArrayList<>();
        for (HnswIndex.SearchResult result : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("dataKey", result.dataKey);
            item.put("distance", result.distance);

            VectorEmbedding embedding = vectorEmbeddingMapper.selectOne(
                new LambdaQueryWrapper<VectorEmbedding>()
                    .eq(VectorEmbedding::getIndexId, indexId)
                    .eq(VectorEmbedding::getDataKey, result.dataKey)
            );
            if (embedding != null && embedding.getMetadata() != null) {
                try {
                    item.put("metadata", objectMapper.readValue(embedding.getMetadata(), Map.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse metadata", e);
                }
            }
            searchResults.add(item);
        }

        return searchResults;
    }

    private HnswIndex getOrLoadIndex(String indexId) {
        return indexCache.computeIfAbsent(indexId, k -> {
            try {
                VectorIndex index = vectorIndexMapper.selectById(k);
                if (index == null || !"ready".equals(index.getStatus())) {
                    return null;
                }
                return loadIndexFromDisk(k, index.getVectorDimension());
            } catch (Exception e) {
                log.error("Failed to load index: {}", k, e);
                return null;
            }
        });
    }

    private void saveIndexToDisk(String indexId, HnswIndex index) throws IOException {
        VectorIndex vectorIndex = vectorIndexMapper.selectById(indexId);
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(vectorIndex.getIndexPath()))) {
            oos.writeObject(index);
        }
    }

    private HnswIndex loadIndexFromDisk(String indexId, int dimension) throws IOException, ClassNotFoundException {
        VectorIndex vectorIndex = vectorIndexMapper.selectById(indexId);
        Path path = Paths.get(vectorIndex.getIndexPath());
        if (!Files.exists(path)) {
            return new HnswIndex(dimension, 16, 100);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(vectorIndex.getIndexPath()))) {
            return (HnswIndex) ois.readObject();
        }
    }

    private byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    public static class HnswIndex implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int dimension;
        private final int M;
        private final int efConstruction;
        private final Map<String, float[]> points;
        private final List<List<Neighbor>> hierarchy;

        public HnswIndex(int dimension, int M, int efConstruction) {
            this.dimension = dimension;
            this.M = M;
            this.efConstruction = efConstruction;
            this.points = new ConcurrentHashMap<>();
            this.hierarchy = new ArrayList<>();
            this.hierarchy.add(new ArrayList<>());
        }

        public void addPoint(String key, float[] vector) {
            points.put(key, vector);
            Neighbor neighbor = new Neighbor(key, vector);
            hierarchy.get(0).add(neighbor);
        }

        public List<SearchResult> search(float[] query, int topK) {
            List<SearchResult> results = new ArrayList<>();
            
            for (Map.Entry<String, float[]> entry : points.entrySet()) {
                float distance = cosineDistance(query, entry.getValue());
                results.add(new SearchResult(entry.getKey(), distance));
            }

            Collections.sort(results, (a, b) -> Float.compare(a.distance, b.distance));
            
            return results.subList(0, Math.min(topK, results.size()));
        }

        private float cosineDistance(float[] a, float[] b) {
            float dotProduct = 0;
            float normA = 0;
            float normB = 0;
            
            for (int i = 0; i < a.length; i++) {
                dotProduct += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            
            float similarity = dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
            return 1 - similarity;
        }

        public static class Neighbor implements Serializable {
            private static final long serialVersionUID = 1L;
            String key;
            float[] vector;

            Neighbor(String key, float[] vector) {
                this.key = key;
                this.vector = vector;
            }
        }

        public static class SearchResult {
            public String dataKey;
            public float distance;

            SearchResult(String dataKey, float distance) {
                this.dataKey = dataKey;
                this.distance = distance;
            }
        }
    }
}
