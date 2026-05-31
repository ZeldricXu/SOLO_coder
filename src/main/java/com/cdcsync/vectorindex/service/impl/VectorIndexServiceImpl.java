package com.cdcsync.vectorindex.service.impl;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.vectorindex.core.HnswIndex;
import com.cdcsync.vectorindex.core.IndexConfig;
import com.cdcsync.vectorindex.core.VectorUtils;
import com.cdcsync.vectorindex.domain.VectorIndex;
import com.cdcsync.vectorindex.mapper.VectorIndexMapper;
import com.cdcsync.vectorindex.service.VectorIndexService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class VectorIndexServiceImpl extends AbstractBaseService<VectorIndex, String, VectorIndexMapper>
        implements VectorIndexService {

    @Value("${cdcsync.vector.index-base-path:/data/cdcsync/vector-indexes}")
    private String indexBasePath;

    private final Map<String, HnswIndex> indexCache = new ConcurrentHashMap<>();

    public VectorIndexServiceImpl(VectorIndexMapper mapper) {
        super(mapper);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down VectorIndexService, closing {} indexes", indexCache.size());
        indexCache.forEach((id, index) -> {
            try {
                index.close();
            } catch (Exception e) {
                log.error("Failed to close index {}: {}", id, e.getMessage());
            }
        });
        indexCache.clear();
    }

    @Override
    protected void setId(VectorIndex entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(VectorIndex entity) {
        return entity.getId();
    }

    @Override
    public void delete(String id) {
        closeIndex(id);
        super.delete(id);
    }

    public void closeIndex(String id) {
        HnswIndex index = indexCache.remove(id);
        if (index != null) {
            try {
                index.close();
                log.info("Index closed and removed from cache: id={}", id);
            } catch (Exception e) {
                log.error("Failed to close index {}: {}", id, e.getMessage());
            }
        }
    }

    @Override
    public void buildIndex(String id, List<float[]> vectors) {
        ValidationUtils.notBlank(id, "id");
        ValidationUtils.notEmpty(vectors, "vectors");

        VectorIndex index = findById(id);
        if (index == null) {
            throw new BusinessException("Vector index not found: " + id);
        }

        for (float[] vector : vectors) {
            VectorUtils.validateDimension(vector, index.getDimension());
        }

        closeIndex(id);

        IndexConfig config = parseConfig(index);
        HnswIndex hnswIndex = null;

        try {
            hnswIndex = new HnswIndex(config);

            for (float[] vector : vectors) {
                hnswIndex.add(vector);
            }

            indexCache.put(id, hnswIndex);

            index.setVectorCount((long) vectors.size());
            index.setStatus("READY");
            index.setLastBuildAt(LocalDateTime.now());
            index.setIndexPath(indexBasePath + "/" + id);
            update(index);

            log.info("Index built successfully: id={}, size={}", id, vectors.size());
        } catch (Exception e) {
            if (hnswIndex != null) {
                try {
                    hnswIndex.close();
                } catch (Exception ex) {
                    log.error("Failed to close index on error: {}", ex.getMessage());
                }
            }
            indexCache.remove(id);
            log.error("Failed to build index {}: {}", id, e.getMessage());
            throw new BusinessException("Failed to build index: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Long> search(String id, float[] queryVector, int topK) {
        ValidationUtils.notBlank(id, "id");
        ValidationUtils.notNull(queryVector, "queryVector");
        ValidationUtils.inRange(topK, 1, 1000, "topK");

        HnswIndex index = getOrLoadIndex(id);
        var results = index.search(queryVector, topK);
        return results.stream()
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void addVectors(String id, List<float[]> vectors) {
        ValidationUtils.notBlank(id, "id");
        ValidationUtils.notEmpty(vectors, "vectors");

        VectorIndex index = findById(id);
        if (index == null) {
            throw new BusinessException("Vector index not found: " + id);
        }
        if (!"READY".equals(index.getStatus())) {
            throw new BusinessException("Index is not ready, status: " + index.getStatus());
        }

        for (float[] vector : vectors) {
            VectorUtils.validateDimension(vector, index.getDimension());
        }

        HnswIndex hnswIndex = getOrLoadIndex(id);
        int addedCount = 0;

        try {
            List<Long> ids = hnswIndex.addBatch(vectors);
            addedCount = ids.size();

            index.setVectorCount((long) hnswIndex.size());
            index.setLastBuildAt(LocalDateTime.now());
            update(index);

            log.info("Vectors added: id={}, added={}, total={}", id, addedCount, hnswIndex.size());
        } catch (Exception e) {
            log.error("Failed to add vectors to index {}: {}", id, e.getMessage());
            throw new BusinessException("Failed to add vectors: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteVectors(String id, List<Long> ids) {
        ValidationUtils.notBlank(id, "id");
        ValidationUtils.notEmpty(ids, "vectorIds");

        HnswIndex index = getOrLoadIndex(id);
        int deletedCount = 0;

        try {
            for (Long vectorId : ids) {
                if (vectorId != null && vectorId >= 0) {
                    index.delete(vectorId);
                    deletedCount++;
                }
            }

            VectorIndex vectorIndex = findById(id);
            if (vectorIndex != null) {
                vectorIndex.setVectorCount((long) index.size());
                vectorIndex.setLastBuildAt(LocalDateTime.now());
                update(vectorIndex);
            }

            log.info("Vectors deleted: id={}, deleted={}, remaining={}", id, deletedCount, index.size());
        } catch (Exception e) {
            log.error("Failed to delete vectors from index {}: {}", id, e.getMessage());
            throw new BusinessException("Failed to delete vectors: " + e.getMessage(), e);
        }
    }

    private HnswIndex getOrLoadIndex(String id) {
        HnswIndex index = indexCache.get(id);
        if (index != null && !index.isClosed()) {
            return index;
        }

        synchronized (this) {
            index = indexCache.get(id);
            if (index != null && !index.isClosed()) {
                return index;
            }

            VectorIndex vectorIndex = findById(id);
            if (vectorIndex == null) {
                throw new BusinessException("Vector index not found: " + id);
            }
            if (!"READY".equals(vectorIndex.getStatus())) {
                throw new BusinessException("Index is not ready, status: " + vectorIndex.getStatus());
            }

            IndexConfig config = parseConfig(vectorIndex);
            index = new HnswIndex(config);
            indexCache.put(id, index);
            log.info("Index loaded from storage: id={}", id);
            return index;
        }
    }

    public ConcurrentHashMap<String, HnswIndex> getIndexCache() {
        return (ConcurrentHashMap<String, HnswIndex>) indexCache;
    }

    private IndexConfig parseConfig(VectorIndex index) {
        IndexConfig config;
        if (index.getConfigJson() != null && !index.getConfigJson().isEmpty()) {
            try {
                config = JSON.parseObject(index.getConfigJson(), IndexConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse config, using default: {}", e.getMessage());
                config = new IndexConfig();
            }
        } else {
            config = new IndexConfig();
        }
        config.setDimension(index.getDimension());
        config.setIndexType(index.getIndexType());
        config.setMetricType(index.getMetricType());
        return config;
    }
}
