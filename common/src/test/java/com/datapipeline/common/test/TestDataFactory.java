package com.datapipeline.common.test;

import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.common.model.Entity;
import com.datapipeline.common.model.RunInstance;
import com.datapipeline.common.model.StatisticsSnapshot;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TestDataFactory {

    private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

    private TestDataFactory() {}

    public static Entity createEntity() {
        return Entity.builder()
                .id(generateId("ent"))
                .type("resource")
                .status("active")
                .attributes(createAttributes())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    public static Entity createEntity(String type) {
        Entity entity = createEntity();
        entity.setType(type);
        return entity;
    }

    public static Entity createEntity(String type, String status) {
        Entity entity = createEntity(type);
        entity.setStatus(status);
        return entity;
    }

    public static ConfigDefinition createConfigDefinition() {
        return ConfigDefinition.builder()
                .configId(generateId("cfg"))
                .namespace("development")
                .version(1)
                .enabled(true)
                .parameter("timeout", 30)
                .parameter("retries", 3)
                .parameter("poolSize", 10)
                .parameter("acquireTimeoutMs", 5000)
                .appliedAt(Instant.now())
                .build();
    }

    public static ConfigDefinition createConfigDefinition(String namespace) {
        ConfigDefinition config = createConfigDefinition();
        config.setNamespace(namespace);
        config.setConfigId("cfg_" + namespace + "_" + RANDOM.nextInt(1000));
        return config;
    }

    public static ConfigDefinition createConfigDefinitionWithRules() {
        ConfigDefinition config = createConfigDefinition();
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(Map.of("type", "UPPERCASE", "params", Map.of("field", "name")));
        rules.add(Map.of("type", "TRIM", "params", Map.of("field", "description")));
        config.setParameter("rules", rules);
        return config;
    }

    public static RunInstance createRunInstance() {
        return RunInstance.builder()
                .runId(generateId("run"))
                .entityId(generateId("ent"))
                .phase("running")
                .progress(0.5)
                .startedAt(Instant.now())
                .build();
    }

    public static RunInstance createRunInstance(String entityId) {
        RunInstance run = createRunInstance();
        run.setEntityId(entityId);
        return run;
    }

    public static RunInstance createCompletedRunInstance() {
        RunInstance run = createRunInstance();
        run.setPhase("completed");
        run.setProgress(1.0);
        run.setCompletedAt(Instant.now());
        return run;
    }

    public static RunInstance createFailedRunInstance(String errorMessage) {
        RunInstance run = createRunInstance();
        run.setPhase("failed");
        run.setCompletedAt(Instant.now());
        run.setErrorDetail(errorMessage);
        return run;
    }

    public static StatisticsSnapshot createStatisticsSnapshot() {
        StatisticsSnapshot snapshot = StatisticsSnapshot.builder()
                .snapshotId(generateId("snap"))
                .timestamp(Instant.now())
                .metrics(new HashMap<>())
                .dimensions(new HashMap<>())
                .build();
        snapshot.metric("throughput", RANDOM.nextDouble(100, 2000));
        snapshot.metric("latency_p99", RANDOM.nextDouble(50, 500));
        snapshot.metric("error_rate", RANDOM.nextDouble(0, 0.1));
        snapshot.dimension("host", "node-" + RANDOM.nextInt(10));
        snapshot.dimension("region", "cn-east");
        return snapshot;
    }

    public static StatisticsSnapshot createStatisticsSnapshot(Map<String, Number> metrics) {
        StatisticsSnapshot snapshot = StatisticsSnapshot.builder()
                .snapshotId(generateId("snap"))
                .timestamp(Instant.now())
                .metrics(new HashMap<>())
                .dimensions(new HashMap<>())
                .build();
        metrics.forEach(snapshot::metric);
        return snapshot;
    }

    public static StatisticsSnapshot createStatisticsSnapshotWithErrorRate(double errorRate) {
        return createStatisticsSnapshot(Map.of(
                "throughput", 1000.0,
                "latency_p99", 100.0,
                "error_rate", errorRate
        ));
    }

    public static Map<String, Object> createPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", generateId("data"));
        payload.put("name", "test data");
        payload.put("description", "  test description with spaces  ");
        payload.put("amount", RANDOM.nextInt(100, 10000));
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    public static Map<String, Object> createPayload(Map<String, Object> overrides) {
        Map<String, Object> payload = createPayload();
        payload.putAll(overrides);
        return payload;
    }

    public static Map<String, Object> createParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("validationMode", "strict");
        params.put("dryRun", false);
        return params;
    }

    public static Map<String, Object> createInvalidParams() {
        Map<String, Object> params = createParams();
        params.put("validationMode", "invalid_mode_that_does_not_exist");
        return params;
    }

    public static Map<String, String> createHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("X-Trace-Id", UUID.randomUUID().toString());
        headers.put("X-Request-Id", generateId("req"));
        return headers;
    }

    public static Map<String, String> createHeadersWithSensitiveData() {
        Map<String, String> headers = createHeaders();
        headers.put("Authorization", "Bearer secret_token_12345");
        headers.put("X-API-Token", "api_key_secret");
        headers.put("password", "my_secret_password");
        return headers;
    }

    public static String generateId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(1000);
    }

    private static Map<String, Object> createAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("priority", RANDOM.nextInt(1, 5));
        attributes.put("category", "test");
        attributes.put("tags", List.of("tag1", "tag2", "tag3"));
        return attributes;
    }

}
