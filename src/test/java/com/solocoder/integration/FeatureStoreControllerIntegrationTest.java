package com.solocoder.integration;

import com.solocoder.FileLifecycleManagerApplication;
import com.solocoder.base.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = FileLifecycleManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class FeatureStoreControllerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Nested
    @DisplayName("特征注册集成测试")
    class FeatureRegistrationIntegrationTests {

        @Test
        @DisplayName("注册特征成功 - 正常流程")
        void registerFeature_Success() {
            Map<String, Object> request = Map.of(
                    "featureName", TestConstants.TEST_FEATURE_NAME,
                    "description", TestConstants.TEST_FEATURE_DESCRIPTION,
                    "schema", TestConstants.TEST_FEATURE_SCHEMA
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("注册特征 - 空名称边界")
        void registerFeature_EmptyName() {
            Map<String, Object> request = Map.of(
                    "featureName", "",
                    "description", TestConstants.TEST_FEATURE_DESCRIPTION,
                    "schema", TestConstants.TEST_FEATURE_SCHEMA
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("注册特征 - 超长名称")
        void registerFeature_LongName() {
            String longName = TestConstants.VERY_LONG_STRING;
            Map<String, Object> request = Map.of(
                    "featureName", longName,
                    "description", TestConstants.TEST_FEATURE_DESCRIPTION,
                    "schema", TestConstants.TEST_FEATURE_SCHEMA
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("注册特征 - 特殊字符名称")
        void registerFeature_SpecialCharsName() {
            String specialName = "feature!@#$%^&*()";
            Map<String, Object> request = Map.of(
                    "featureName", specialName,
                    "description", TestConstants.TEST_FEATURE_DESCRIPTION,
                    "schema", TestConstants.TEST_FEATURE_SCHEMA
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("注册特征 - 空Schema")
        void registerFeature_EmptySchema() {
            Map<String, Object> request = Map.of(
                    "featureName", TestConstants.TEST_FEATURE_NAME,
                    "description", TestConstants.TEST_FEATURE_DESCRIPTION,
                    "schema", Map.of()
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("注册特征 - 复杂Schema")
        void registerFeature_ComplexSchema() {
            Map<String, Object> complexSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string"),
                            "age", Map.of("type", "integer", "minimum", 0),
                            "tags", Map.of("type", "array")
                    ),
                    "required", List.of("name")
            );

            Map<String, Object> request = Map.of(
                    "featureName", "complex_feature",
                    "description", "A complex feature",
                    "schema", complexSchema
            );

            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("特征入库集成测试")
    class FeatureIngestIntegrationTests {

        @Test
        @DisplayName("特征入库成功 - 正常流程")
        void ingestFeatures_Success() {
            Map<String, Object> request = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", TestConstants.TEST_FEATURES,
                    "eventTime", TestConstants.TEST_EVENT_TIME.toString()
            );

            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("特征入库 - 空特征Map")
        void ingestFeatures_EmptyFeatures() {
            Map<String, Object> request = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", Map.of(),
                    "eventTime", TestConstants.TEST_EVENT_TIME.toString()
            );

            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("特征入库 - 大量特征")
        void ingestFeatures_ManyFeatures() {
            Map<String, Object> manyFeatures = new java.util.HashMap<>();
            for (int i = 0; i < 100; i++) {
                manyFeatures.put("feature_" + i, i * 10);
            }

            Map<String, Object> request = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", manyFeatures,
                    "eventTime", Instant.now().toString()
            );

            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("特征入库 - 各种类型特征值")
        void ingestFeatures_VariousValueTypes() {
            Map<String, Object> features = Map.of(
                    "int_feature", 42,
                    "long_feature", 10000000000L,
                    "double_feature", 3.14159,
                    "string_feature", "test_value",
                    "boolean_feature", true,
                    "list_feature", List.of(1, 2, 3),
                    "map_feature", Map.of("nested", "value")
            );

            Map<String, Object> request = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", features,
                    "eventTime", Instant.now().toString()
            );

            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("特征入库 - 空实体ID")
        void ingestFeatures_EmptyEntityId() {
            Map<String, Object> request = Map.of(
                    "entityId", "",
                    "features", TestConstants.TEST_FEATURES,
                    "eventTime", Instant.now().toString()
            );

            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("在线特征查询集成测试")
    class OnlineFeatureQueryIntegrationTests {

        @Test
        @DisplayName("查询在线特征 - 正常流程")
        void getOnlineFeatures_Success() {
            Map<String, Object> ingestRequest = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", TestConstants.TEST_FEATURES,
                    "eventTime", Instant.now().toString()
            );
            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ingestRequest)
                    .exchange()
                    .expectStatus().isOk();

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/online")
                            .queryParam("entityId", TestConstants.TEST_ENTITY_ID)
                            .queryParam("featureNames", String.join(",", TestConstants.TEST_FEATURE_NAMES))
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("查询在线特征 - 空特征列表")
        void getOnlineFeatures_EmptyFeatureNames() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/online")
                            .queryParam("entityId", TestConstants.TEST_ENTITY_ID)
                            .queryParam("featureNames", "")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("查询在线特征 - 特征不存在返回空")
        void getOnlineFeatures_NonExistentFeatures() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/online")
                            .queryParam("entityId", "nonexistent_entity")
                            .queryParam("featureNames", "nonexistent_feature")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data").exists();
        }

        @Test
        @DisplayName("查询在线特征 - 大量特征名称")
        void getOnlineFeatures_ManyFeatureNames() {
            List<String> manyNames = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                manyNames.add("feature_" + i);
            }

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/online")
                            .queryParam("entityId", TestConstants.TEST_ENTITY_ID)
                            .queryParam("featureNames", String.join(",", manyNames))
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("一致性检查集成测试")
    class ConsistencyCheckIntegrationTests {

        @Test
        @DisplayName("一致性检查 - 正常流程")
        void checkConsistency_Success() {
            Map<String, Object> ingestRequest = Map.of(
                    "entityId", TestConstants.TEST_ENTITY_ID,
                    "features", TestConstants.TEST_FEATURES,
                    "eventTime", Instant.now().toString()
            );
            webTestClient.post()
                    .uri("/api/v1/features/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ingestRequest)
                    .exchange()
                    .expectStatus().isOk();

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/consistency")
                            .queryParam("entityId", TestConstants.TEST_ENTITY_ID)
                            .queryParam("featureName", "user_click_count")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data").isBoolean();
        }

        @Test
        @DisplayName("一致性检查 - 空参数")
        void checkConsistency_EmptyParams() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/features/consistency")
                            .queryParam("entityId", "")
                            .queryParam("featureName", "")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("同步集成测试")
    class SyncIntegrationTests {

        @Test
        @DisplayName("同步在线到离线 - 正常流程")
        void syncOnlineToOffline_Success() {
            Map<String, Object> registerRequest = Map.of(
                    "featureName", "sync_test_feature",
                    "description", "Test feature for sync",
                    "schema", TestConstants.TEST_FEATURE_SCHEMA
            );
            webTestClient.post()
                    .uri("/api/v1/features/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(registerRequest)
                    .exchange()
                    .expectStatus().isOk();

            webTestClient.post()
                    .uri("/api/v1/features/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("featureName", "sync_test_feature"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("同步在线到离线 - 空特征名")
        void syncOnlineToOffline_EmptyFeatureName() {
            webTestClient.post()
                    .uri("/api/v1/features/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("featureName", ""))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }
}
