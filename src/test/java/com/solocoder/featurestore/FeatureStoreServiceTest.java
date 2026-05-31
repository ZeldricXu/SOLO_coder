package com.solocoder.featurestore;

import com.solocoder.application.service.FeatureStoreService;
import com.solocoder.base.TestConstants;
import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.FeatureStorePort;
import com.solocoder.domain.port.StructuredLoggerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureStoreServiceTest {

    @Mock
    private FeatureStorePort featureStorePort;

    @Mock
    private StructuredLoggerPort logger;

    @InjectMocks
    private FeatureStoreService featureStoreService;

    @Nested
    @DisplayName("边界条件测试 - registerFeature")
    class RegisterFeatureBoundaryTests {

        @Test
        @DisplayName("正常注册特征成功")
        void registerFeature_Success() {
            when(featureStorePort.registerFeature(anyString(), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("空特征名称处理")
        void registerFeature_EmptyFeatureName(String featureName) {
            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    featureName,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超长特征名称")
        void registerFeature_VeryLongFeatureName() {
            String longFeatureName = TestConstants.VERY_LONG_STRING;
            when(featureStorePort.registerFeature(eq(longFeatureName), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    longFeatureName,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("特殊字符特征名称")
        void registerFeature_SpecialCharsFeatureName() {
            String specialName = "feature!" + TestConstants.SPECIAL_CHARS_STRING;
            when(featureStorePort.registerFeature(eq(specialName), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    specialName,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("null Schema")
        void registerFeature_NullSchema() {
            when(featureStorePort.registerFeature(anyString(), anyString(), isNull()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    null
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超复杂Schema")
        void registerFeature_ComplexSchema() {
            Map<String, Object> complexSchema = new HashMap<>();
            Map<String, Object> nested = new HashMap<>();
            nested.put("type", "object");
            nested.put("properties", Map.of(
                    "field1", Map.of("type", "string"),
                    "field2", Map.of("type", "integer", "minimum", 0)
            ));
            complexSchema.put("schema", nested);
            complexSchema.put("validation", Map.of("required", List.of("field1")));

            when(featureStorePort.registerFeature(anyString(), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    complexSchema
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超大Schema")
        void registerFeature_LargeSchema() {
            Map<String, Object> largeSchema = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                largeSchema.put("field_" + i, Map.of("type", "string", "index", i));
            }

            when(featureStorePort.registerFeature(anyString(), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    largeSchema
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - getOnlineFeatures")
    class GetOnlineFeaturesBoundaryTests {

        @Test
        @DisplayName("正常获取在线特征")
        void getOnlineFeatures_Success() {
            Map<String, Object> expectedFeatures = new HashMap<>(TestConstants.TEST_FEATURES);
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.just(expectedFeatures));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isEqualTo(expectedFeatures);
                    })
                    .verifyComplete();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("空实体ID处理")
        void getOnlineFeatures_EmptyEntityId(String entityId) {
            when(featureStorePort.getOnlineFeatures(eq(entityId), anyList()))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    entityId,
                    TestConstants.TEST_FEATURE_NAMES
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("空特征名称列表")
        void getOnlineFeatures_EmptyFeatureNames() {
            when(featureStorePort.getOnlineFeatures(anyString(), eq(Collections.emptyList())))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    Collections.emptyList()
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("大量特征名称查询")
        void getOnlineFeatures_ManyFeatureNames() {
            List<String> manyFeatures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                manyFeatures.add("feature_" + i);
            }
            when(featureStorePort.getOnlineFeatures(anyString(), eq(manyFeatures)))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    manyFeatures
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("部分特征不存在时返回已存在的特征")
        void getOnlineFeatures_PartialFeatures() {
            Map<String, Object> partialFeatures = Map.of("feature1", 42);
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.just(partialFeatures));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    List.of("feature1", "feature2", "feature3")
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).hasSize(1);
                        assertThat(response.getData().get("feature1")).isEqualTo(42);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - ingestFeatures")
    class IngestFeaturesBoundaryTests {

        @Test
        @DisplayName("正常入库特征")
        void ingestFeatures_Success() {
            when(featureStorePort.ingestFeatures(anyString(), anyMap(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURES,
                    TestConstants.TEST_EVENT_TIME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("空特征Map")
        void ingestFeatures_EmptyFeatures() {
            when(featureStorePort.ingestFeatures(anyString(), eq(Collections.emptyMap()), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    Collections.emptyMap(),
                    TestConstants.TEST_EVENT_TIME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("超大特征Map")
        void ingestFeatures_LargeFeatures() {
            Map<String, Object> largeFeatures = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                largeFeatures.put("feature_" + i, i * 100);
            }
            when(featureStorePort.ingestFeatures(anyString(), anyMap(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    largeFeatures,
                    TestConstants.TEST_EVENT_TIME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("各种类型特征值")
        void ingestFeatures_VariousValueTypes() {
            Map<String, Object> variousFeatures = Map.of(
                    "int_feature", 42,
                    "long_feature", 10000000000L,
                    "double_feature", 3.14159,
                    "string_feature", "value",
                    "boolean_feature", true,
                    "list_feature", List.of(1, 2, 3),
                    "map_feature", Map.of("nested", "value")
            );
            when(featureStorePort.ingestFeatures(anyString(), anyMap(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    variousFeatures,
                    TestConstants.TEST_EVENT_TIME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("null特征值")
        void ingestFeatures_NullFeatureValue() {
            Map<String, Object> featuresWithNull = new HashMap<>();
            featuresWithNull.put("null_feature", null);
            featuresWithNull.put("normal_feature", 42);

            when(featureStorePort.ingestFeatures(anyString(), anyMap(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    featuresWithNull,
                    TestConstants.TEST_EVENT_TIME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("边界条件测试 - getOfflineFeatures")
    class GetOfflineFeaturesBoundaryTests {

        @Test
        @DisplayName("正常获取离线特征")
        void getOfflineFeatures_Success() {
            Flux<Map<String, Object>> expectedFlux = Flux.just(
                    Map.of("feature1", 10, "timestamp", Instant.now().toString()),
                    Map.of("feature1", 20, "timestamp", Instant.now().toString())
            );
            when(featureStorePort.getOfflineFeatures(anyString(), anyList(), any(), any()))
                    .thenReturn(expectedFlux);

            Mono<ApiResponse<Flux<Map<String, Object>>>> result = featureStoreService.getOfflineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES,
                    Instant.now().minusSeconds(3600),
                    Instant.now()
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("时间范围倒序处理")
        void getOfflineFeatures_ReversedTimeRange() {
            when(featureStorePort.getOfflineFeatures(anyString(), anyList(), any(), any()))
                    .thenReturn(Flux.empty());

            Mono<ApiResponse<Flux<Map<String, Object>>>> result = featureStoreService.getOfflineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES,
                    Instant.now(),
                    Instant.now().minusSeconds(3600)
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }

        @Test
        @DisplayName("相同开始结束时间")
        void getOfflineFeatures_SameStartTimeEndTime() {
            Instant now = Instant.now();
            when(featureStorePort.getOfflineFeatures(anyString(), anyList(), eq(now), eq(now)))
                    .thenReturn(Flux.empty());

            Mono<ApiResponse<Flux<Map<String, Object>>>> result = featureStoreService.getOfflineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES,
                    now,
                    now
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - registerFeature")
    class RegisterFeatureExceptionTests {

        @Test
        @DisplayName("注册抛出异常时返回422错误")
        void registerFeature_Exception_Returns422() {
            when(featureStorePort.registerFeature(anyString(), anyString(), any()))
                    .thenReturn(Mono.error(new RuntimeException("Schema validation failed")));

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(422);
                        assertThat(response.getMessage()).isNotBlank();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("注册成功时不返回数据")
        void registerFeature_Success_ReturnsVoid() {
            when(featureStorePort.registerFeature(anyString(), anyString(), any()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.registerFeature(
                    TestConstants.TEST_FEATURE_NAME,
                    TestConstants.TEST_FEATURE_DESCRIPTION,
                    TestConstants.TEST_FEATURE_SCHEMA
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - getOnlineFeatures")
    class GetOnlineFeaturesExceptionTests {

        @Test
        @DisplayName("查询抛出异常时返回500错误")
        void getOnlineFeatures_Exception_Returns500() {
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection failed")));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(500))
                    .verifyComplete();
        }

        @Test
        @DisplayName("特征不存在时返回空Map而非404")
        void getOnlineFeatures_NoFeatures_ReturnsEmptyMap() {
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    List.of("nonexistent_feature")
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - checkConsistency")
    class CheckConsistencyExceptionTests {

        @Test
        @DisplayName("一致性检查通过")
        void checkConsistency_Passed() {
            when(featureStorePort.checkConsistency(anyString(), anyString()))
                    .thenReturn(Mono.just(true));

            Mono<ApiResponse<Boolean>> result = featureStoreService.checkConsistency(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAME
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("一致性检查失败")
        void checkConsistency_Failed() {
            when(featureStorePort.checkConsistency(anyString(), anyString()))
                    .thenReturn(Mono.just(false));

            Mono<ApiResponse<Boolean>> result = featureStoreService.checkConsistency(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAME
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("一致性检查抛出异常")
        void checkConsistency_Exception_Returns500() {
            when(featureStorePort.checkConsistency(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Check failed")));

            Mono<ApiResponse<Boolean>> result = featureStoreService.checkConsistency(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAME
            );

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(500))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("异常路径测试 - syncOnlineToOffline")
    class SyncOnlineToOfflineExceptionTests {

        @Test
        @DisplayName("同步抛出异常时返回500错误")
        void syncOnlineToOffline_Exception_Returns500() {
            when(featureStorePort.syncOnlineToOffline(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Sync failed")));

            Mono<ApiResponse<Void>> result = featureStoreService.syncOnlineToOffline(TestConstants.TEST_FEATURE_NAME);

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(500))
                    .verifyComplete();
        }

        @Test
        @DisplayName("同步成功时返回200")
        void syncOnlineToOffline_Success_Returns200() {
            when(featureStorePort.syncOnlineToOffline(anyString()))
                    .thenReturn(Mono.empty());

            Mono<ApiResponse<Void>> result = featureStoreService.syncOnlineToOffline(TestConstants.TEST_FEATURE_NAME);

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(200))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("外部依赖故障模拟测试")
    class ExternalDependencyFailureTests {

        @Test
        @DisplayName("Redis连接失败时优雅降级")
        void getOnlineFeatures_RedisDown_GracefulDegradation() {
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(500);
                        assertThat(response.getMessage()).isNotBlank();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("特征入库超时处理")
        void ingestFeatures_Timeout_Handled() {
            when(featureStorePort.ingestFeatures(anyString(), anyMap(), any()))
                    .thenReturn(Mono.never());

            Mono<ApiResponse<Void>> result = featureStoreService.ingestFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURES,
                    TestConstants.TEST_EVENT_TIME
            ).timeout(java.time.Duration.ofMillis(100))
             .onErrorResume(e -> Mono.just(ApiResponse.error(504, "操作超时")));

            StepVerifier.create(result)
                    .assertNext(response -> assertThat(response.getCode()).isEqualTo(504))
                    .verifyComplete();
        }

        @Test
        @DisplayName("离线存储不可用时在线查询不受影响")
        void getOnlineFeatures_OfflineStoreDown_OnlineStillWorks() {
            Map<String, Object> expectedFeatures = new HashMap<>(TestConstants.TEST_FEATURES);
            when(featureStorePort.getOnlineFeatures(anyString(), anyList()))
                    .thenReturn(Mono.just(expectedFeatures));

            Mono<ApiResponse<Map<String, Object>>> result = featureStoreService.getOnlineFeatures(
                    TestConstants.TEST_ENTITY_ID,
                    TestConstants.TEST_FEATURE_NAMES
            );

            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.getCode()).isEqualTo(200);
                        assertThat(response.getData()).isNotEmpty();
                    })
                    .verifyComplete();
        }
    }
}
