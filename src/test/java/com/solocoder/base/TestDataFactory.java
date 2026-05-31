package com.solocoder.base;

import com.solocoder.domain.model.CoreEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TestDataFactory {

    public static CoreEntity createTestFileEntity(String fileId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("fileName", "test_" + fileId + ".txt");
        attributes.put("fileSize", 1024L);
        attributes.put("storageClass", "standard");
        attributes.put("filePath", "/data/" + fileId + ".txt");

        return CoreEntity.builder()
                .id(fileId)
                .type("file")
                .status("active")
                .attributes(attributes)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    public static CoreEntity createExpiredFileEntity(String fileId) {
        CoreEntity entity = createTestFileEntity(fileId);
        entity.setCreatedAt(Instant.now().minusSeconds(86400 * 100));
        entity.setStatus("expired");
        return entity;
    }

    public static CoreEntity createArchivedFileEntity(String fileId) {
        CoreEntity entity = createTestFileEntity(fileId);
        entity.setStatus("archived");
        entity.getAttributes().put("storageClass", "GLACIER");
        entity.getAttributes().put("archivedAt", Instant.now().toString());
        return entity;
    }

    public static Map<String, Object> createTestFeatureValue(String featureName, Object value) {
        Map<String, Object> feature = new HashMap<>();
        feature.put("name", featureName);
        feature.put("value", value);
        feature.put("timestamp", Instant.now().toString());
        return feature;
    }

    public static Map<String, Object> createLargeFeatureMap(int size) {
        Map<String, Object> features = new HashMap<>();
        for (int i = 0; i < size; i++) {
            features.put("feature_" + i, i * 100);
        }
        return features;
    }

    public static String generateRandomFileId() {
        return "file_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateRandomEntityId() {
        return "entity_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateRandomFeatureName() {
        return "feature_" + UUID.randomUUID().toString().replace("-", "");
    }

    private TestDataFactory() {
    }
}
