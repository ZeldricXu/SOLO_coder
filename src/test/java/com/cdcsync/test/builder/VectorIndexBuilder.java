package com.cdcsync.test.builder;

import com.cdcsync.vectorindex.domain.VectorIndex;

import java.time.LocalDateTime;
import java.util.UUID;

public class VectorIndexBuilder {

    private final VectorIndex index;

    private VectorIndexBuilder() {
        this.index = new VectorIndex();
    }

    public static VectorIndexBuilder aVectorIndex() {
        return new VectorIndexBuilder();
    }

    public VectorIndexBuilder withDefaults() {
        return withId("vi_" + UUID.randomUUID().toString().substring(0, 8))
                .withName("Test Vector Index")
                .withDimension(128)
                .withIndexType("HNSW")
                .withMetricType("COSINE")
                .withVectorCount(0L)
                .withStatus("CREATING")
                .withConfigJson("{\"m\": 16, \"efConstruction\": 200, \"efSearch\": 50}")
                .withCreatedAt(LocalDateTime.now())
                .withUpdatedAt(LocalDateTime.now())
                .withDeleted(0);
    }

    public VectorIndexBuilder withId(String id) {
        index.setId(id);
        return this;
    }

    public VectorIndexBuilder withName(String name) {
        index.setName(name);
        return this;
    }

    public VectorIndexBuilder withDimension(Integer dimension) {
        index.setDimension(dimension);
        return this;
    }

    public VectorIndexBuilder withIndexType(String indexType) {
        index.setIndexType(indexType);
        return this;
    }

    public VectorIndexBuilder withMetricType(String metricType) {
        index.setMetricType(metricType);
        return this;
    }

    public VectorIndexBuilder withVectorCount(Long vectorCount) {
        index.setVectorCount(vectorCount);
        return this;
    }

    public VectorIndexBuilder withIndexPath(String indexPath) {
        index.setIndexPath(indexPath);
        return this;
    }

    public VectorIndexBuilder withConfigJson(String configJson) {
        index.setConfigJson(configJson);
        return this;
    }

    public VectorIndexBuilder withStatus(String status) {
        index.setStatus(status);
        return this;
    }

    public VectorIndexBuilder withLastBuildAt(LocalDateTime lastBuildAt) {
        index.setLastBuildAt(lastBuildAt);
        return this;
    }

    public VectorIndexBuilder withCreatedAt(LocalDateTime createdAt) {
        index.setCreatedAt(createdAt);
        return this;
    }

    public VectorIndexBuilder withUpdatedAt(LocalDateTime updatedAt) {
        index.setUpdatedAt(updatedAt);
        return this;
    }

    public VectorIndexBuilder withDeleted(Integer deleted) {
        index.setDeleted(deleted);
        return this;
    }

    public VectorIndex build() {
        return index;
    }
}
