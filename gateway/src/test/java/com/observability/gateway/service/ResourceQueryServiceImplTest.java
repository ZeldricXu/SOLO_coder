package com.observability.gateway.service;

import com.observability.common.dto.ResourceStatusResponse;
import com.observability.common.entity.ResourceEntity;
import com.observability.common.entity.RunInstanceEntity;
import com.observability.gateway.service.impl.ResourceQueryServiceImpl;
import com.observability.dal.repository.ResourceRepository;
import com.observability.dal.repository.RunInstanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResourceQueryService 测试")
class ResourceQueryServiceImplTest {

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private RunInstanceRepository runInstanceRepository;

    @InjectMocks
    private ResourceQueryServiceImpl resourceQueryService;

    @Nested
    @DisplayName("getResourceStatus 测试")
    class GetResourceStatusTests {

        @Test
        @DisplayName("正常场景：资源存在且有运行实例")
        void getResourceStatus_WithResourceAndRunInstance_Success() {
            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("test-123");
            resource.setStatus("running");

            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setProgress(0.75);
            runInstance.setErrorDetail(null);

            when(resourceRepository.findByResourceId("test-123"))
                    .thenReturn(Optional.of(resource));
            when(runInstanceRepository.findLatestByEntityId("test-123"))
                    .thenReturn(Optional.of(runInstance));

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("test-123");

            StepVerifier.create(result)
                    .expectNextMatches(response ->
                            response.getId().equals("test-123") &&
                                    response.getStatus().equals("running") &&
                                    response.getProgress() == 0.75 &&
                                    response.getErrorDetail() == null
                    )
                    .verifyComplete();

            verify(resourceRepository, times(1)).findByResourceId("test-123");
            verify(runInstanceRepository, times(1)).findLatestByEntityId("test-123");
        }

        @Test
        @DisplayName("正常场景：资源存在但无运行实例")
        void getResourceStatus_WithResourceButNoRunInstance_Success() {
            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("test-456");
            resource.setStatus("stopped");

            when(resourceRepository.findByResourceId("test-456"))
                    .thenReturn(Optional.of(resource));
            when(runInstanceRepository.findLatestByEntityId("test-456"))
                    .thenReturn(Optional.empty());

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("test-456");

            StepVerifier.create(result)
                    .expectNextMatches(response ->
                            response.getId().equals("test-456") &&
                                    response.getStatus().equals("stopped") &&
                                    response.getProgress() == null &&
                                    response.getErrorDetail() == null
                    )
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：资源不存在")
        void getResourceStatus_ResourceNotFound_ThrowsException() {
            when(resourceRepository.findByResourceId("nonexistent"))
                    .thenReturn(Optional.empty());

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("nonexistent");

            StepVerifier.create(result)
                    .expectErrorMatches(e ->
                            e instanceof RuntimeException &&
                                    e.getMessage().contains("Resource not found")
                    )
                    .verify();
        }

        @Test
        @DisplayName("边界场景：资源ID为空字符串")
        void getResourceStatus_EmptyResourceId_ThrowsException() {
            when(resourceRepository.findByResourceId(""))
                    .thenReturn(Optional.empty());

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("");

            StepVerifier.create(result)
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("边界场景：资源ID为null")
        void getResourceStatus_NullResourceId_ThrowsException() {
            when(resourceRepository.findByResourceId(null))
                    .thenReturn(Optional.empty());

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus(null);

            StepVerifier.create(result)
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("异常场景：运行实例查询失败")
        void getResourceStatus_RunInstanceQueryFails_StillReturnsStatus() {
            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("test-789");
            resource.setStatus("running");

            when(resourceRepository.findByResourceId("test-789"))
                    .thenReturn(Optional.of(resource));
            when(runInstanceRepository.findLatestByEntityId("test-789"))
                    .thenThrow(new RuntimeException("DB connection failed"));

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("test-789");

            StepVerifier.create(result)
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("异常场景：资源查询抛出异常")
        void getResourceStatus_RepositoryThrowsException_Propagates() {
            when(resourceRepository.findByResourceId(anyString()))
                    .thenThrow(new RuntimeException("Database unavailable"));

            Mono<ResourceStatusResponse> result = resourceQueryService.getResourceStatus("test-123");

            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().equals("Database unavailable"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("findById 测试")
    class FindByIdTests {

        @Test
        @DisplayName("正常场景：资源存在")
        void findById_ResourceExists_ReturnsOptional() {
            ResourceEntity resource = new ResourceEntity();
            resource.setResourceId("test-123");

            when(resourceRepository.findByResourceId("test-123"))
                    .thenReturn(Optional.of(resource));

            StepVerifier.create(resourceQueryService.findById("test-123"))
                    .expectNextMatches(opt -> opt.isPresent() && opt.get().getResourceId().equals("test-123"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("正常场景：资源不存在")
        void findById_ResourceNotFound_ReturnsEmpty() {
            when(resourceRepository.findByResourceId("nonexistent"))
                    .thenReturn(Optional.empty());

            StepVerifier.create(resourceQueryService.findById("nonexistent"))
                    .expectNextMatches(Optional::isEmpty)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：ID为空字符串")
        void findById_EmptyId_ReturnsEmpty() {
            when(resourceRepository.findByResourceId(""))
                    .thenReturn(Optional.empty());

            StepVerifier.create(resourceQueryService.findById(""))
                    .expectNextMatches(Optional::isEmpty)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("exists 测试")
    class ExistsTests {

        @Test
        @DisplayName("正常场景：资源存在")
        void exists_ResourceExists_ReturnsTrue() {
            when(resourceRepository.existsByResourceId("test-123"))
                    .thenReturn(true);

            StepVerifier.create(resourceQueryService.exists("test-123"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("正常场景：资源不存在")
        void exists_ResourceNotFound_ReturnsFalse() {
            when(resourceRepository.existsByResourceId("nonexistent"))
                    .thenReturn(false);

            StepVerifier.create(resourceQueryService.exists("nonexistent"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：存储层抛出异常")
        void exists_RepositoryThrowsException_Propagates() {
            when(resourceRepository.existsByResourceId(anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            StepVerifier.create(resourceQueryService.exists("test-123"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }
}
