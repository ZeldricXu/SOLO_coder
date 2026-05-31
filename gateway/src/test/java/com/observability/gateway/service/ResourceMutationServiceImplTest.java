package com.observability.gateway.service;

import com.observability.common.context.RequestContext;
import com.observability.common.context.RequestContextHolder;
import com.observability.common.dto.BatchOperationRequest;
import com.observability.common.dto.ResourceCreateRequest;
import com.observability.common.entity.ResourceEntity;
import com.observability.common.entity.RunInstanceEntity;
import com.observability.common.exception.BusinessException;
import com.observability.dal.repository.ResourceRepository;
import com.observability.dal.repository.RunInstanceRepository;
import com.observability.gateway.service.impl.ResourceMutationServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResourceMutationService 测试")
class ResourceMutationServiceImplTest {

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private RunInstanceRepository runInstanceRepository;

    @Mock
    private ResourceFactory resourceFactory;

    @InjectMocks
    private ResourceMutationServiceImpl resourceMutationService;

    private static MockedStatic<RequestContextHolder> contextHolderMock;

    @BeforeAll
    static void setup() {
        contextHolderMock = mockStatic(RequestContextHolder.class);
    }

    @AfterAll
    static void cleanup() {
        contextHolderMock.close();
    }

    @BeforeEach
    void initContext() {
        RequestContext context = RequestContext.create("trace-123")
                .namespace("test-ns")
                .userId("user-123");
        contextHolderMock.when(RequestContextHolder::get).thenReturn(Mono.just(context));
    }

    @Nested
    @DisplayName("createResource 测试")
    class CreateResourceTests {

        @Test
        @DisplayName("正常场景：创建资源成功")
        void createResource_ValidRequest_Success() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setNamespace("custom-ns");

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-123");
            resource.setStatus("provisioning");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setRunId("run-123");

            when(resourceFactory.createResourceEntity(any(), any())
                    .thenReturn(resource);
            when(resourceRepository.save(any()))
                    .thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())
                    .thenReturn(runInstance);
            when(runInstanceRepository.save(any()))
                    .thenReturn(runInstance);

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectNextMatches(data ->
                            data.get("id").equals("rsc-123") &&
                                    data.get("status").equals("provisioning") &&
                                    data.get("runId").equals("run-123")
                    )
                    .verifyComplete();

            verify(resourceRepository, times(1)).save(any());
            verify(runInstanceRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("边界场景：请求类型为空")
        void createResource_EmptyType_ThrowsValidationError() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("");

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectErrorMatches(e ->
                            e instanceof BusinessException &&
                                    ((BusinessException) e).getCode() == 422 &&
                                    e.getMessage().contains("Resource type is required")
                    )
                    .verify();

            verify(resourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("边界场景：请求类型为null")
        void createResource_NullType_ThrowsValidationError() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType(null);

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("边界场景：请求类型为空白字符串")
        void createResource_BlankType_ThrowsValidationError() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("   ");

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("边界场景：超长类型名称")
        void createResource_LongTypeName_Success() {
            String longType = "a".repeat(1000);
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType(longType);

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-long");
            resource.setStatus("provisioning");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setRunId("run-long");

            when(resourceFactory.createResourceEntity(any(), any())).thenReturn(resource);
            when(resourceRepository.save(any())).thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())).thenReturn(runInstance);
            when(runInstanceRepository.save(any())).thenReturn(runInstance);

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectNextMatches(data -> data.get("id").equals("rsc-long"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：特殊字符类型名称")
        void createResource_SpecialCharsInType_Success() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow-123_测试@#$");

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-special");
            resource.setStatus("provisioning");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setRunId("run-special");

            when(resourceFactory.createResourceEntity(any(), any())).thenReturn(resource);
            when(resourceRepository.save(any())).thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())).thenReturn(runInstance);
            when(runInstanceRepository.save(any())).thenReturn(runInstance);

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectNextMatches(data -> data.get("id").equals("rsc-special"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：资源保存失败")
        void createResource_ResourceSaveFails_ThrowsException() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");

            ResourceEntity resource = new ResourceEntity();
            when(resourceFactory.createResourceEntity(any(), any())).thenReturn(resource);
            when(resourceRepository.save(any())).thenThrow(new RuntimeException("DB connection failed"));

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().equals("DB connection failed"))
                    .verify();

            verify(runInstanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("异常场景：运行实例保存失败")
        void createResource_RunInstanceSaveFails_ThrowsException() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-123");

            RunInstanceEntity runInstance = new RunInstanceEntity();

            when(resourceFactory.createResourceEntity(any(), any())).thenReturn(resource);
            when(resourceRepository.save(any())).thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())).thenReturn(runInstance);
            when(runInstanceRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().equals("DB error"))
                    .verify();
        }

        @Test
        @DisplayName("边界场景：命名空间为null时使用默认值")
        void createResource_NullNamespace_UsesContextNamespace() {
            ResourceCreateRequest request = new ResourceCreateRequest();
            request.setType("workflow");
            request.setNamespace(null);

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-123");
            resource.setStatus("provisioning");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setRunId("run-123");

            when(resourceFactory.createResourceEntity(any(), eq("test-ns"))).thenReturn(resource);
            when(resourceRepository.save(any())).thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())).thenReturn(runInstance);
            when(runInstanceRepository.save(any())).thenReturn(runInstance);

            Mono<Map<String, Object>> result = resourceMutationService.createResource(request);

            StepVerifier.create(result)
                    .expectNextMatches(data -> data.get("id").equals("rsc-123"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("batchOperation 测试")
    class BatchOperationTests {

        @Test
        @DisplayName("正常场景：批量操作成功")
        void batchOperation_MultipleOperations_Success() {
            BatchOperationRequest request = new BatchOperationRequest();
            List<BatchOperationRequest.Operation> operations = new ArrayList<>();

            BatchOperationRequest.Operation op1 = new BatchOperationRequest.Operation();
            op1.setAction("start");
            op1.setId("rsc-001");
            operations.add(op1);

            BatchOperationRequest.Operation op2 = new BatchOperationRequest.Operation();
            op2.setAction("stop");
            op2.setId("rsc-002");
            operations.add(op2);

            request.setOperations(operations);

            when(resourceRepository.updateStatus(eq("rsc-001"), eq("running"))).thenReturn(new ResourceEntity());
            when(resourceRepository.updateStatus(eq("rsc-002"), eq("stopped"))).thenReturn(new ResourceEntity());

            StepVerifier.create(resourceMutationService.batchOperation(request))
                    .expectNextMatches(response ->
                            response.getResults().size() == 2 &&
                                    response.getResults().get(0).isSuccess() &&
                                    response.getResults().get(1).isSuccess()
                    )
                    .verifyComplete();

            verify(resourceRepository, times(1)).updateStatus("rsc-001", "running");
            verify(resourceRepository, times(1)).updateStatus("rsc-002", "stopped");
        }

        @Test
        @DisplayName("边界场景：空操作列表")
        void batchOperation_EmptyOperations_ReturnsEmptyResults() {
            BatchOperationRequest request = new BatchOperationRequest();
            request.setOperations(new ArrayList<>());

            StepVerifier.create(resourceMutationService.batchOperation(request))
                    .expectNextMatches(response -> response.getResults().isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：部分操作失败")
        void batchOperation_PartialFailures_ReturnsMixedResults() {
            BatchOperationRequest request = new BatchOperationRequest();
            List<BatchOperationRequest.Operation> operations = new ArrayList<>();

            BatchOperationRequest.Operation op1 = new BatchOperationRequest.Operation();
            op1.setAction("start");
            op1.setId("rsc-001");
            operations.add(op1);

            BatchOperationRequest.Operation op2 = new BatchOperationRequest.Operation();
            op2.setAction("invalid");
            op2.setId("rsc-002");
            operations.add(op2);

            request.setOperations(operations);

            when(resourceRepository.updateStatus(eq("rsc-001"), eq("running"))).thenReturn(new ResourceEntity());

            StepVerifier.create(resourceMutationService.batchOperation(request))
                    .expectNextMatches(response ->
                            response.getResults().size() == 2 &&
                                    response.getResults().get(0).isSuccess() &&
                                    !response.getResults().get(1).isSuccess() &&
                                    response.getResults().get(1).getMessage().contains("Unknown action")
                    )
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：未知操作类型")
        void batchOperation_UnknownAction_ReturnsFailure() {
            BatchOperationRequest request = new BatchOperationRequest();
            BatchOperationRequest.Operation op = new BatchOperationRequest.Operation();
            op.setAction("unknown_action");
            op.setId("rsc-001");
            request.setOperations(List.of(op));

            StepVerifier.create(resourceMutationService.batchOperation(request))
                    .expectNextMatches(response -> !response.getResults().get(0).isSuccess())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("单独资源操作测试")
    class IndividualResourceOperationTests {

        @Test
        @DisplayName("正常场景：启动资源")
        void startResource_Success() {
            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-123");
            when(resourceRepository.updateStatus("rsc-123", "running")).thenReturn(resource);

            StepVerifier.create(resourceMutationService.startResource("rsc-123"))
                    .verifyComplete();

            verify(resourceRepository, times(1)).updateStatus("rsc-123", "running");
        }

        @Test
        @DisplayName("边界场景：启动空ID资源")
        void startResource_EmptyId_ThrowsException() {
            when(resourceRepository.updateStatus(eq(""), anyString()))
                    .thenThrow(new RuntimeException("Resource not found"));

            StepVerifier.create(resourceMutationService.startResource(""))
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("正常场景：停止资源")
        void stopResource_Success() {
            ResourceEntity resource = new ResourceEntity();
            when(resourceRepository.updateStatus("rsc-123", "stopped")).thenReturn(resource);

            StepVerifier.create(resourceMutationService.stopResource("rsc-123"))
                    .verifyComplete();

            verify(resourceRepository, times(1)).updateStatus("rsc-123", "stopped");
        }

        @Test
        @DisplayName("正常场景：重启资源")
        void restartResource_Success() {
            ResourceEntity resource = new ResourceEntity();
            when(resourceRepository.updateStatus("rsc-123", "provisioning")).thenReturn(resource);

            StepVerifier.create(resourceMutationService.restartResource("rsc-123"))
                    .verifyComplete();

            verify(resourceRepository, times(1)).updateStatus("rsc-123", "provisioning");
        }

        @Test
        @DisplayName("正常场景：删除资源")
        void deleteResource_Success() {
            StepVerifier.create(resourceMutationService.deleteResource("rsc-123"))
                    .verifyComplete();

            verify(resourceRepository, times(1)).deleteByResourceId("rsc-123");
        }

        @Test
        @DisplayName("异常场景：删除失败")
        void deleteResource_RepositoryFails_ThrowsException() {
            doThrow(new RuntimeException("DB error"))
                    .when(resourceRepository).deleteByResourceId("rsc-123");

            StepVerifier.create(resourceMutationService.deleteResource("rsc-123"))
                    .expectErrorMatches(e -> e.getMessage().equals("DB error"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("并发场景测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发创建多个资源")
        void createResource_ConcurrentCreation_ThreadSafe() throws InterruptedException {
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("rsc-concurrent");
            resource.setStatus("provisioning");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setRunId("run-concurrent");

            when(resourceFactory.createResourceEntity(any(), any())).thenReturn(resource);
            when(resourceRepository.save(any())).thenReturn(resource);
            when(resourceFactory.createRunInstance(any(), any())).thenReturn(runInstance);
            when(runInstanceRepository.save(any())).thenReturn(runInstance);

            List<Thread> threads = IntStream.range(0, threadCount)
                    .mapToObj(i -> new Thread(() -> {
                        try {
                            ResourceCreateRequest request = new ResourceCreateRequest();
                            request.setType("workflow-" + i);
                            resourceMutationService.createResource(request).block();
                            successCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }))
                    .collect(Collectors.toList());

            threads.forEach(Thread::start);
            boolean completed = latch.await(10, TimeUnit.SECONDS);

            Assertions.assertTrue(completed, "All threads completed");
            Assertions.assertEquals(threadCount, successCount.get());
            verify(resourceRepository, times(threadCount)).save(any());
        }

        @Test
        @DisplayName("并发批量操作")
        void batchOperation_ConcurrentOperations_ThreadSafe() throws InterruptedException {
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(resourceRepository.updateStatus(any(), any())).thenReturn(new ResourceEntity());

            List<Thread> threads = IntStream.range(0, threadCount)
                    .mapToObj(i -> new Thread(() -> {
                        try {
                            BatchOperationRequest request = new BatchOperationRequest();
                            BatchOperationRequest.Operation op = new BatchOperationRequest.Operation();
                            op.setAction("start");
                            op.setId("rsc-" + i);
                            request.setOperations(List.of(op));
                            resourceMutationService.batchOperation(request).block();
                            successCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }))
                    .collect(Collectors.toList());

            threads.forEach(Thread::start);
            boolean completed = latch.await(10, TimeUnit.SECONDS);

            Assertions.assertTrue(completed);
            Assertions.assertEquals(threadCount, successCount.get());
        }
    }
}
