package com.datapipeline.core;

import com.datapipeline.common.event.EventPublisher;
import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.common.model.RunInstance;
import com.datapipeline.common.test.TestDataFactory;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.core.persistence.ResultPersister;
import com.datapipeline.core.resource.PooledResource;
import com.datapipeline.core.resource.ResourcePool;
import com.datapipeline.core.transform.DataTransformer;
import com.datapipeline.core.validation.ParameterValidator;
import com.datapipeline.data.repository.ConfigRepository;
import com.datapipeline.data.repository.ResourceRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoreProcessorTest {

    @Mock
    private ParameterValidator validator;

    @Mock
    private ConfigRepository configRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourcePool resourcePool;

    @Mock
    private DataTransformer transformer;

    @Mock
    private ResultPersister persister;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private MetricsRecorder metricsRecorder;

    private CoreProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CoreProcessor(
                validator,
                configRepository,
                resourceRepository,
                resourcePool,
                transformer,
                persister,
                eventPublisher,
                metricsRecorder
        );
    }

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTests {

        @Test
        @DisplayName("正常处理请求应返回成功结果")
        void testSuccessfulProcessing() throws Exception {
            Map<String, Object> payload = TestDataFactory.createPayload();
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinitionWithRules();
            RunInstance runInstance = TestDataFactory.createRunInstance();
            PooledResource resource = PooledResource.builder().id("res_test").build();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenReturn(payload);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_001")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(payload)
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.SUCCESS);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getRequestId()).isEqualTo("req_test_001");
            assertThat(result.getData()).isNotNull();

            verify(validator).validate(params);
            verify(validator).validateConfig(config.getParameters());
            verify(resourcePool).acquire(anyLong(), eq(TimeUnit.MILLISECONDS));
            verify(resourcePool).release(resource);
            verify(persister).createRun("test-namespace");
            verify(persister).markRunning(runInstance.getRunId());
            verify(persister).updateProgress(eq(runInstance.getRunId()), eq(1.0));
            verify(transformer).transform(any(), anyList());
            verify(eventPublisher).publish(any());
            verify(metricsRecorder).recordTraceContext(any());
        }

        @Test
        @DisplayName("默认配置应在配置不存在时创建")
        void testDefaultConfigCreation() throws Exception {
            Map<String, Object> payload = TestDataFactory.createPayload();
            Map<String, Object> params = TestDataFactory.createParams();
            PooledResource resource = PooledResource.builder().id("res_test").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("new-namespace")).thenReturn(Optional.empty());
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("new-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenReturn(payload);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_002")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("new-namespace")
                    .params(params)
                    .payload(payload)
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.SUCCESS);
            verify(configRepository).save(any(ConfigDefinition.class));
        }

        @Test
        @DisplayName("空负载应返回空结果")
        void testNullPayloadProcessing() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_003")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(null)
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.SUCCESS);
            assertThat(result.getData()).isNotNull();
        }

    }

    @Nested
    @DisplayName("异常流程测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("参数验证错误应返回验证错误结果")
        void testValidationError() throws Exception {
            Map<String, Object> params = TestDataFactory.createInvalidParams();
            ValidationError validationError = new ValidationError("Invalid validationMode", "validationMode");

            doThrow(validationError).when(validator).validate(params);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_004")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.ERROR);
            assertThat(result.getMessage()).contains("Validation");
            verify(resourcePool, never()).acquire(anyLong(), any());
            verify(metricsRecorder).recordTraceContext(any());
        }

        @Test
        @DisplayName("资源获取超时应返回超时结果")
        void testResourceAcquireTimeout() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new TimeoutException("Timeout"));

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_005")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.TIMEOUT);
            assertThat(result.getMessage()).contains("超时");
            verify(persister).persistTimeout(eq("req_test_005"), anyString());
        }

        @Test
        @DisplayName("业务超时异常应返回超时结果")
        void testBusinessTimeoutException() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenThrow(BusinessException.timeout("Processing timeout"));

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_006")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.TIMEOUT);
            verify(resourcePool).release(resource);
        }

        @Test
        @DisplayName("内部异常应返回错误结果并执行回滚")
        void testInternalException() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenThrow(new RuntimeException("Unexpected error"));

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_007")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.ERROR);
            assertThat(result.getMessage()).contains("内部处理错误");
            verify(resourcePool).release(resource);
        }

        @Test
        @DisplayName("已取消的请求应返回错误")
        void testCancelledRequest() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_008")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();
            ctx.cancel();

            ProcessResult result = processor.execute(ctx);

            assertThat(result.getStatus()).isEqualTo(ProcessResult.Status.ERROR);
            verify(resourcePool).release(resource);
        }

    }

    @Nested
    @DisplayName("资源释放完整性测试")
    class ResourceReleaseTests {

        @Test
        @DisplayName("成功路径中资源应被释放")
        void testResourceReleasedOnSuccess() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test_001").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenReturn(TestDataFactory.createPayload());

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_009")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            processor.execute(ctx);

            verify(resourcePool).release(resource);
        }

        @Test
        @DisplayName("转换异常时资源应被释放")
        void testResourceReleasedOnTransformException() throws Exception {
            Map<String, Object> params = TestDataFactory.createParams();
            ConfigDefinition config = TestDataFactory.createConfigDefinition();
            PooledResource resource = PooledResource.builder().id("res_test_002").build();
            RunInstance runInstance = TestDataFactory.createRunInstance();

            when(configRepository.findLatestByNamespace("test-namespace")).thenReturn(Optional.of(config));
            when(resourcePool.acquire(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(resource);
            when(persister.createRun("test-namespace")).thenReturn(runInstance);
            when(transformer.transform(any(), anyList())).thenThrow(new RuntimeException("Transform failed"));

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_010")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            processor.execute(ctx);

            verify(resourcePool).release(resource);
        }

        @Test
        @DisplayName("空资源不应尝试释放")
        void testNullResourceNotReleased() {
            Map<String, Object> params = TestDataFactory.createInvalidParams();
            ValidationError validationError = new ValidationError("Invalid params", "params");

            doThrow(validationError).when(validator).validate(params);

            RequestContext ctx = RequestContext.builder()
                    .requestId("req_test_011")
                    .traceId(UUID.randomUUID().toString())
                    .namespace("test-namespace")
                    .params(params)
                    .payload(TestDataFactory.createPayload())
                    .build();

            processor.execute(ctx);

            verify(resourcePool, never()).release(any());
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        private CoreProcessor concurrentProcessor;
        private ParameterValidator realValidator;
        private ConfigRepository realConfigRepo;
        private ResourcePool realResourcePool;
        private DataTransformer realTransformer;
        private ResultPersister realPersister;
        private EventPublisher realEventPublisher;
        private MetricsRecorder realMetricsRecorder;

        @BeforeEach
        void setUpConcurrency() {
            realValidator = new ParameterValidator();
            realConfigRepo = mock(ConfigRepository.class);
            realResourcePool = new ResourcePool(5, () -> PooledResource.builder()
                    .id("res_" + UUID.randomUUID())
                    .build());
            realTransformer = new DataTransformer();
            realPersister = mock(ResultPersister.class);
            realEventPublisher = mock(EventPublisher.class);
            realMetricsRecorder = mock(MetricsRecorder.class);

            when(realConfigRepo.findLatestByNamespace(anyString())).thenReturn(
                    Optional.of(TestDataFactory.createConfigDefinition())
            );
            when(realPersister.createRun(anyString())).thenReturn(TestDataFactory.createRunInstance());

            concurrentProcessor = new CoreProcessor(
                    realValidator,
                    realConfigRepo,
                    mock(ResourceRepository.class),
                    realResourcePool,
                    realTransformer,
                    realPersister,
                    realEventPublisher,
                    realMetricsRecorder
            );
        }

        @Test
        @DisplayName("并发请求不应导致资源泄漏")
        void testConcurrentProcessingNoResourceLeak() throws Exception {
            int threadCount = 10;
            int iterationsPerThread = 100;

            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            try {
                                RequestContext ctx = RequestContext.builder()
                                        .requestId("req_" + threadId + "_" + j)
                                        .traceId(UUID.randomUUID().toString())
                                        .namespace("concurrent-test")
                                        .params(TestDataFactory.createParams())
                                        .payload(TestDataFactory.createPayload())
                                        .build();

                                ProcessResult result = concurrentProcessor.execute(ctx);
                                if (result.isSuccess()) {
                                    successCount.incrementAndGet();
                                } else {
                                    failureCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                failureCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(realResourcePool.getAcquiredCount()).as("所有资源应被释放").isEqualTo(0);
            assertThat(failureCount.get()).as("所有请求应成功处理").isLessThan(iterationsPerThread * threadCount);
            assertThat(successCount.get()).as("大部分请求应成功").isGreaterThan(0);
        }

    }

}
