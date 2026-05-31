package com.observability.gateway.service;

import com.observability.common.dto.ResourceCreateRequest;
import com.observability.common.entity.ResourceEntity;
import com.observability.common.entity.RunInstanceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResourceFactory 测试")
class ResourceFactoryTest {

    private final ResourceFactory factory = new ResourceFactory();

    @Nested
    @DisplayName("createResourceEntity 测试")
    class CreateResourceEntityTests {

        @Test
        @DisplayName("正常场景：创建资源实体")
        void createResourceEntity_ValidRequest_Success() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setNamespace("test-ns");

            Map<String, Object> config = new HashMap<>();
            config.put("timeout", 30);
            config.put("retries", 3);
            request.setConfig(config);

            Map<String, String> labels = new HashMap<>();
            labels.put("env", "test");
            labels.put("team", "devops");
            request.setLabels(labels);

            ResourceEntity entity = factory.createResourceEntity(request, "default-ns");

            assertThat(entity).isNotNull();
            assertThat(entity.getResourceId()).isNotNull().startsWith("rsc_");
            assertThat(entity.getType()).isEqualTo("workflow");
            assertThat(entity.getStatus()).isEqualTo("provisioning");
            assertThat(entity.getNamespace()).isEqualTo("test-ns");
            assertThat(entity.getAttributes()).isNotNull();
            assertThat(entity.getConfig()).isNotNull();
            assertThat(entity.getLabels()).isNotNull();
        }

        @Test
        @DisplayName("边界场景：命名空间为null时使用默认值")
        void createResourceEntity_NullNamespace_UsesDefault() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setNamespace(null);

            ResourceEntity entity = factory.createResourceEntity(request, "fallback-ns");

            assertThat(entity.getNamespace()).isEqualTo("fallback-ns");
        }

        @Test
        @DisplayName("边界场景：配置为null")
        void createResourceEntity_NullConfig_StillCreatesEntity() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setConfig(null);

            ResourceEntity entity = factory.createResourceEntity(request, "test-ns");

            assertThat(entity).isNotNull();
            assertThat(entity.getConfig()).isNotNull();
        }

        @Test
        @DisplayName("边界场景：标签为null")
        void createResourceEntity_NullLabels_StillCreatesEntity() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setLabels(null);

            ResourceEntity entity = factory.createResourceEntity(request, "test-ns");

            assertThat(entity).isNotNull();
            assertThat(entity.getLabels()).isNotNull();
        }

        @Test
        @DisplayName("边界场景：超长类型名称")
        void createResourceEntity_LongType_TruncatedOrNot() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("a".repeat(10000));

            ResourceEntity entity = factory.createResourceEntity(request, "test-ns");

            assertThat(entity.getType()).hasSize(10000);
        }

        @Test
        @DisplayName("边界场景：配置包含特殊字符")
        void createResourceEntity_SpecialCharsInType_Success() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow-测试-123_@#$");

            ResourceEntity entity = factory.createResourceEntity(request, "test-ns");

            assertThat(entity.getType()).isEqualTo("workflow-测试-123_@#$");
        }

        @Test
        @DisplayName("边界场景：配置包含大量配置项")
        void createResourceEntity_LargeConfig_Success() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");

            Map<String, Object> config = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                config.put("key" + i, "value" + i);
            }
            request.setConfig(config);

            ResourceEntity entity = factory.createResourceEntity(request, "test-ns");

            assertThat(entity).isNotNull();
            assertThat(entity.getConfig()).isNotNull();
        }
    }

    @Nested
    @DisplayName("createRunInstance 测试")
    class CreateRunInstanceTests {

        @Test
        @DisplayName("正常场景：创建运行实例")
        void createRunInstance_ValidParams_Success() {
            RunInstanceEntity entity = factory.createRunInstance("rsc-123", "trace-456");

            assertThat(entity).isNotNull();
            assertThat(entity.getRunId()).isNotNull().startsWith("run_");
            assertThat(entity.getEntityId()).isEqualTo("rsc-123");
            assertThat(entity.getPhase()).isEqualTo("initializing");
            assertThat(entity.getProgress()).isEqualTo(0.0);
            assertThat(entity.getStartedAt()).isNotNull();
            assertThat(entity.getTraceId()).isEqualTo("trace-456");
        }

        @Test
        @DisplayName("边界场景：资源ID为空")
        void createRunInstance_EmptyResourceId_StillCreates() {
            RunInstanceEntity entity = factory.createRunInstance("", "trace-123");

            assertThat(entity).isNotNull();
            assertThat(entity.getEntityId()).isEqualTo("");
        }

        @Test
        @DisplayName("边界场景：TraceId为空")
        void createRunInstance_EmptyTraceId_StillCreates() {
            RunInstanceEntity entity = factory.createRunInstance("rsc-123", "");

            assertThat(entity).isNotNull();
            assertThat(entity.getTraceId()).isEqualTo("");
        }

        @Test
        @DisplayName("边界场景：资源ID为null")
        void createRunInstance_NullResourceId_StillCreates() {
            RunInstanceEntity entity = factory.createRunInstance(null, "trace-123");

            assertThat(entity).isNotNull();
            assertThat(entity.getEntityId()).isNull();
        }

        @Test
        @DisplayName("每次调用生成唯一RunId")
        void createRunInstance_MultipleCalls_UniqueIds() {
            RunInstanceEntity e1 = factory.createRunInstance("rsc-1", "trace-1");
            RunInstanceEntity e2 = factory.createRunInstance("rsc-2", "trace-2");

            assertThat(e1.getRunId()).isNotEqualTo(e2.getRunId());
        }
    }
}
