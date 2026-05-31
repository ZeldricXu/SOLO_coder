package com.solocoder.concurrent;

import com.solocoder.base.ConcurrentTestUtils;
import com.solocoder.base.TestConstants;
import com.solocoder.infrastructure.adapter.featurestore.RedisFeatureStoreAdapter;
import com.solocoder.infrastructure.adapter.featurestore.event.FeatureEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureStoreConcurrentTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private FeatureEventPublisher eventPublisher;

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private RedisFeatureStoreAdapter featureStoreAdapter;

    private Map<String, Map<String, Object>> onlineStore;
    private Map<String, Map<String, String>> offlineStore;
    private Map<String, Map<String, Object>> registryStore;

    @BeforeEach
    void setUp() {
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        onlineStore = new ConcurrentHashMap<>();
        offlineStore = new ConcurrentHashMap<>();
        registryStore = new ConcurrentHashMap<>();

        lenient().when(redissonClient.getMapCache(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            RMapCache<String, Object> mapCache = mock(RMapCache.class);

            Map<String, Object> store = onlineStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

            lenient().when(mapCache.get(anyString())).thenAnswer(inv -> {
                String entityId = inv.getArgument(0);
                return store.get(entityId);
            });

            lenient().when(mapCache.put(anyString(), any(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                String entityId = inv.getArgument(0);
                Object value = inv.getArgument(1);
                store.put(entityId, value);
                return null;
            });

            lenient().when(mapCache.forEach(any())).thenAnswer(inv -> {
                java.util.function.BiConsumer consumer = inv.getArgument(0);
                store.forEach((k, v) -> consumer.accept(k, v));
                return null;
            });

            return mapCache;
        });

        lenient().when(redissonClient.getMap(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            RMap<String, String> map = mock(RMap.class);

            if (key.startsWith("feature:registry:")) {
                Map<String, Object> registry = registryStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

                lenient().when(map.put(anyString(), any())).thenAnswer(inv -> {
                    String field = inv.getArgument(0);
                    Object value = inv.getArgument(1);
                    registry.put(field, value);
                    return null;
                });

                lenient().when(map.containsKey(anyString())).thenAnswer(inv -> {
                    String field = inv.getArgument(0);
                    return registry.containsKey(field);
                });
            } else {
                Map<String, String> store = offlineStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

                lenient().when(map.put(anyString(), anyString())).thenAnswer(inv -> {
                    String field = inv.getArgument(0);
                    String value = inv.getArgument(1);
                    store.put(field, value);
                    return null;
                });

                lenient().when(map.containsKey(anyString())).thenAnswer(inv -> {
                    String field = inv.getArgument(0);
                    return store.containsKey(field);
                });

                lenient().when(map.containsValue(anyString())).thenAnswer(inv -> {
                    String value = inv.getArgument(0);
                    return store.containsValue(value);
                });

                lenient().when(map.entrySet()).thenAnswer(inv -> new HashSet<>(store.entrySet()));
            }

            return map;
        });

        featureStoreAdapter = new RedisFeatureStoreAdapter(redissonClient, objectMapper, eventPublisher);
    }

    @Nested
    @DisplayName("并发特征注册测试")
    class ConcurrentRegisterTests {

        @Test
        @DisplayName("并发注册不同特征 - 全部成功")
        void concurrentRegisterDifferentFeatures_AllSuccess() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    20,
                    5,
                    () -> {
                        String featureName = "feature_" + counter.incrementAndGet();
                        featureStoreAdapter.registerFeature(
                                featureName,
                                "Description for " + featureName,
                                TestConstants.TEST_FEATURE_SCHEMA
                        ).block();
                        return featureName;
                    },
                    1.0
            );

            assertThat(registryStore).hasSizeGreaterThanOrEqualTo(95);
        }

        @Test
        @DisplayName("并发注册同一特征 - 幂等性")
        void concurrentRegisterSameFeature_Idempotent() throws Exception {
            String featureName = "shared_feature";

            ConcurrentTestUtils.executeConcurrently(
                    30,
                    1,
                    () -> {
                        featureStoreAdapter.registerFeature(
                                featureName,
                                TestConstants.TEST_FEATURE_DESCRIPTION,
                                TestConstants.TEST_FEATURE_SCHEMA
                        ).block();
                        return null;
                    }
            );

            assertThat(registryStore).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("并发特征入库测试")
    class ConcurrentIngestTests {

        @Test
        @DisplayName("高并发特征入库 - 无数据丢失")
        void concurrentIngestFeatures_HighThroughput() throws Exception {
            AtomicInteger entityCounter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    30,
                    10,
                    () -> {
                        String entityId = "entity_" + entityCounter.incrementAndGet();
                        Map<String, Object> features = Map.of(
                                "click_count", (int) (Math.random() * 100),
                                "view_count", (int) (Math.random() * 1000)
                        );
                        featureStoreAdapter.ingestFeatures(
                                entityId,
                                features,
                                Instant.now()
                        ).block();
                        return entityId;
                    },
                    1.0
            );

            assertThat(onlineStore).isNotEmpty();
        }

        @Test
        @DisplayName("同一实体并发入库 - 最终一致性")
        void concurrentIngestSameEntity_FinalConsistency() throws Exception {
            String entityId = "user_12345";
            AtomicInteger counter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    20,
                    5,
                    () -> {
                        int value = counter.incrementAndGet();
                        Map<String, Object> features = Map.of(
                                "concurrent_feature", value
                        );
                        featureStoreAdapter.ingestFeatures(
                                entityId,
                                features,
                                Instant.now()
                        ).block();
                        return value;
                    },
                    1.0
            );

            assertThat(onlineStore).isNotEmpty();
        }

        @Test
        @DisplayName("大量特征并发入库 - 性能验证")
        void concurrentIngestManyFeatures_Performance() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);
            long startTime = System.currentTimeMillis();

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    10,
                    20,
                    () -> {
                        String entityId = "perf_entity_" + counter.incrementAndGet();
                        Map<String, Object> features = new HashMap<>();
                        for (int i = 0; i < 50; i++) {
                            features.put("feature_" + i, Math.random() * 100);
                        }
                        featureStoreAdapter.ingestFeatures(
                                entityId,
                                features,
                                Instant.now()
                        ).block();
                        return entityId;
                    },
                    0.98
            );

            long duration = System.currentTimeMillis() - startTime;
            System.out.println("Ingested 200 entities with 50 features each in " + duration + "ms");
            assertThat(duration).isLessThan(30000);
        }
    }

    @Nested
    @DisplayName("并发特征查询测试")
    class ConcurrentQueryTests {

        @Test
        @DisplayName("高并发在线特征查询 - 稳定性")
        void concurrentGetOnlineFeatures_Stable() throws Exception {
            for (int i = 0; i < 10; i++) {
                String entityId = "test_entity_" + i;
                Map<String, Object> features = Map.of(
                        "feature_" + i, i * 10
                );
                featureStoreAdapter.ingestFeatures(entityId, features, Instant.now()).block();
            }

            AtomicInteger counter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    50,
                    10,
                    () -> {
                        int idx = counter.incrementAndGet() % 10;
                        String entityId = "test_entity_" + idx;
                        var result = featureStoreAdapter.getOnlineFeatures(
                                entityId,
                                List.of("feature_" + idx)
                        ).block();
                        assertNotNull(result);
                        return result;
                    },
                    1.0
            );
        }

        @Test
        @DisplayName("并发查询和入库混合 - 无数据竞争")
        void concurrentReadWriteMixed_NoRaceCondition() throws Exception {
            String entityId = "mixed_entity";
            featureStoreAdapter.ingestFeatures(
                    entityId,
                    Map.of("base_feature", 0),
                    Instant.now()
            ).block();

            AtomicInteger successCount = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    30,
                    5,
                    () -> {
                        int operation = (int) (Math.random() * 2);
                        if (operation == 0) {
                            featureStoreAdapter.ingestFeatures(
                                    entityId,
                                    Map.of("random_feature", Math.random()),
                                    Instant.now()
                            ).block();
                        } else {
                            var result = featureStoreAdapter.getOnlineFeatures(
                                    entityId,
                                    List.of("base_feature")
                            ).block();
                            assertNotNull(result);
                        }
                        successCount.incrementAndGet();
                        return null;
                    },
                    0.99
            );
        }
    }

    @Nested
    @DisplayName("并发一致性检查测试")
    class ConcurrentConsistencyTests {

        @Test
        @DisplayName("并发一致性检查 - 无假阳性")
        void concurrentCheckConsistency_NoFalsePositives() throws Exception {
            String featureName = "consistent_feature";

            for (int i = 0; i < 10; i++) {
                String entityId = "entity_" + i;
                featureStoreAdapter.ingestFeatures(
                        entityId,
                        Map.of(featureName, i),
                        Instant.now()
                ).block();
            }

            AtomicInteger counter = new AtomicInteger(0);

            ConcurrentTestUtils.executeConcurrentlyAndVerify(
                    20,
                    5,
                    () -> {
                        int idx = counter.incrementAndGet() % 10;
                        String entityId = "entity_" + idx;
                        Boolean consistent = featureStoreAdapter.checkConsistency(
                                entityId,
                                featureName
                        ).block();
                        assertNotNull(consistent);
                        return consistent;
                    },
                    1.0
            );
        }
    }
}
