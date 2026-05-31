package com.chaoslab.modules.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.BaseTest;
import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.entity.DnsUpstream;
import com.chaoslab.mapper.DnsAsyncTaskMapper;
import com.chaoslab.mapper.DnsUpstreamMapper;
import com.chaoslab.mapper.DnsZoneMapper;
import com.chaoslab.modules.dns.dto.AsyncResolveRequest;
import com.chaoslab.modules.dns.event.DnsResolveCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DnsAsyncService 单元测试")
class DnsAsyncServiceTest extends BaseTest {

    @Mock
    private DnsAsyncTaskMapper asyncTaskMapper;

    @Mock
    private DnsUpstreamMapper upstreamMapper;

    @Mock
    private DnsZoneMapper zoneMapper;

    @Mock
    private DnsProxyService proxyService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ThreadPoolTaskExecutor dnsAsyncExecutor;

    @InjectMocks
    private DnsAsyncService asyncService;

    private final Map<String, DnsAsyncTask> taskStore = new ConcurrentHashMap<>();
    private final Map<String, DnsUpstream> upstreamStore = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        taskStore.clear();
        upstreamStore.clear();
        taskCounter.set(0);
        setupMockBehaviors();
        setupAsyncExecutor();
    }

    private void setupMockBehaviors() {
        when(asyncTaskMapper.insert(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
            DnsAsyncTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(System.currentTimeMillis() + taskCounter.incrementAndGet());
            }
            taskStore.put(task.getTaskId(), task);
            return 1;
        });

        when(asyncTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return taskStore.values().stream().findFirst().orElse(null);
        });

        when(asyncTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(taskStore.values());
        });

        when(asyncTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) taskStore.size();
        });

        when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
            DnsAsyncTask task = invocation.getArgument(0);
            taskStore.put(task.getTaskId(), task);
            return 1;
        });

        when(upstreamMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return upstreamStore.values().stream().findFirst().orElse(null);
        });

        when(upstreamMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(upstreamStore.values());
        });

        when(proxyService.resolve(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String domain = invocation.getArgument(0);
                    String type = invocation.getArgument(1);
                    Map<String, Object> record = new HashMap<>();
                    record.put("domain", domain);
                    record.put("type", type);
                    record.put("value", "127.0.0.1");
                    record.put("ttl", 300);
                    return Mono.just(Map.of(
                            "status", "NOERROR",
                            "records", List.of(record),
                            "source", "async-test"
                    ));
                });
    }

    private void setupAsyncExecutor() {
        dnsAsyncExecutor = new ThreadPoolTaskExecutor();
        dnsAsyncExecutor.setCorePoolSize(2);
        dnsAsyncExecutor.setMaxPoolSize(4);
        dnsAsyncExecutor.setQueueCapacity(100);
        dnsAsyncExecutor.setThreadNamePrefix("test-dns-async-");
        dnsAsyncExecutor.initialize();
    }

    // ==================== 异步解析提交测试 ====================

    @Nested
    @DisplayName("异步解析提交测试")
    class AsyncSubmitTests {

        @Test
        @DisplayName("提交异步解析 - 成功")
        void submitAsyncResolve_Success() {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("test.example.com");
            request.setQueryType("A");
            request.setPriority("normal");
            request.setRequestedBy("test-user");

            Mono<DnsAsyncTask> result = asyncService.submitAsyncResolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(task -> {
                        assertThat(task.getTaskId()).isNotNull().startsWith("dat-");
                        assertThat(task.getDomain()).isEqualTo("test.example.com");
                        assertThat(task.getQueryType()).isEqualTo("A");
                        assertThat(task.getStatus()).isEqualTo("PENDING");
                        assertThat(task.getPriority()).isEqualTo("normal");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("提交异步解析 - 高优先级")
        void submitAsyncResolve_HighPriority() {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("high-priority.example.com");
            request.setQueryType("AAAA");
            request.setPriority("high");

            Mono<DnsAsyncTask> result = asyncService.submitAsyncResolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(task -> {
                        assertThat(task.getPriority()).isEqualTo("high");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("提交异步解析 - 带Webhook回调")
        void submitAsyncResolve_WithWebhook() {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("webhook.example.com");
            request.setCallbackType("webhook");
            request.setCallbackUrl("https://callback.example.com/webhook");
            request.setCallbackHeaders(Map.of("X-API-Key", "test-key"));

            Mono<DnsAsyncTask> result = asyncService.submitAsyncResolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(task -> {
                        assertThat(task.getCallbackType()).isEqualTo("webhook");
                        assertThat(task.getCallbackUrl()).isEqualTo("https://callback.example.com/webhook");
                        assertThat(task.getCallbackHeaders()).containsEntry("X-API-Key", "test-key");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("提交异步解析 - 带事件通知")
        void submitAsyncResolve_WithEvent() {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("event.example.com");
            request.setCallbackType("event");
            request.setEventName("DNS_RESOLVED");
            request.setEventPayload(Map.of("customKey", "customValue"));

            Mono<DnsAsyncTask> result = asyncService.submitAsyncResolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(task -> {
                        assertThat(task.getCallbackType()).isEqualTo("event");
                        assertThat(task.getEventName()).isEqualTo("DNS_RESOLVED");
                        assertThat(task.getEventPayload()).containsEntry("customKey", "customValue");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("批量提交解析 - 成功")
        void batchSubmit_Success() {
            List<AsyncResolveRequest> requests = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                AsyncResolveRequest request = new AsyncResolveRequest();
                request.setDomain("batch-" + i + ".example.com");
                request.setQueryType("A");
                requests.add(request);
            }

            Mono<List<DnsAsyncTask>> result = asyncService.batchSubmit(requests);

            StepVerifier.create(result)
                    .expectNextMatches(tasks -> {
                        assertThat(tasks).hasSize(5);
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 异步任务执行测试 ====================

    @Nested
    @DisplayName("异步任务执行测试")
    class AsyncExecutionTests {

        @Test
        @DisplayName("异步任务执行 - 成功完成")
        void executeAsyncTask_Success() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<DnsAsyncTask> completedTask = new AtomicReference<>();

            when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
                DnsAsyncTask task = invocation.getArgument(0);
                taskStore.put(task.getTaskId(), task);
                if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                    completedTask.set(task);
                    latch.countDown();
                }
                return 1;
            });

            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest())
                    .block();
            assertNotNull(task);

            asyncService.processTask(task);

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertTrue(completed, "任务应在超时前完成");
            assertNotNull(completedTask.get());
            assertEquals("COMPLETED", completedTask.get().getStatus());
            assertNotNull(completedTask.get().getResult());
            assertNotNull(completedTask.get().getDurationMs());
        }

        @Test
        @DisplayName("异步任务执行 - 失败重试")
        void executeAsyncTask_FailureRetry() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<DnsAsyncTask> finalTask = new AtomicReference<>();

            when(proxyService.resolve(anyString(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("DNS查询失败"))
                    .thenThrow(new RuntimeException("DNS查询失败"))
                    .thenAnswer(invocation -> {
                        String domain = invocation.getArgument(0);
                        return Mono.just(Map.of(
                                "status", "NOERROR",
                                "records", List.of(Map.of("domain", domain, "value", "127.0.0.1"))
                        ));
                    });

            when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
                DnsAsyncTask task = invocation.getArgument(0);
                taskStore.put(task.getTaskId(), task);
                if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                    finalTask.set(task);
                    latch.countDown();
                }
                return 1;
            });

            AsyncResolveRequest request = buildRequest();
            request.setMaxRetries(3);
            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            assertNotNull(task);

            asyncService.processTask(task);

            boolean completed = latch.await(15, TimeUnit.SECONDS);
            assertTrue(completed);
            assertNotNull(finalTask.get());
            assertEquals("COMPLETED", finalTask.get().getStatus());
            assertEquals(2, finalTask.get().getRetryCount());
        }

        @Test
        @DisplayName("异步任务 - 超过最大重试次数失败")
        void executeAsyncTask_ExceedMaxRetries() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<DnsAsyncTask> finalTask = new AtomicReference<>();

            when(proxyService.resolve(anyString(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("DNS查询持续失败"));

            when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
                DnsAsyncTask task = invocation.getArgument(0);
                taskStore.put(task.getTaskId(), task);
                if ("FAILED".equals(task.getStatus())) {
                    finalTask.set(task);
                    latch.countDown();
                }
                return 1;
            });

            AsyncResolveRequest request = buildRequest();
            request.setMaxRetries(2);
            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            assertNotNull(task);

            asyncService.processTask(task);

            boolean completed = latch.await(15, TimeUnit.SECONDS);
            assertTrue(completed);
            assertNotNull(finalTask.get());
            assertEquals("FAILED", finalTask.get().getStatus());
            assertEquals(2, finalTask.get().getRetryCount());
            assertNotNull(finalTask.get().getErrorMessage());
            assertThat(finalTask.get().getErrorMessage()).contains("DNS查询持续失败");
        }

        @Test
        @DisplayName("高优先级任务 - 优先执行")
        void executeAsyncTask_HighPriorityFirst() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

            when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
                DnsAsyncTask task = invocation.getArgument(0);
                taskStore.put(task.getTaskId(), task);
                if ("COMPLETED".equals(task.getStatus())) {
                    executionOrder.add(task.getPriority());
                    latch.countDown();
                }
                return 1;
            });

            AsyncResolveRequest normalRequest = buildRequest();
            normalRequest.setPriority("normal");
            normalRequest.setDomain("normal.example.com");

            AsyncResolveRequest highRequest = buildRequest();
            highRequest.setPriority("high");
            highRequest.setDomain("high.example.com");

            DnsAsyncTask normalTask = asyncService.submitAsyncResolve(normalRequest).block();
            DnsAsyncTask highTask = asyncService.submitAsyncResolve(highRequest).block();
            assertNotNull(normalTask);
            assertNotNull(highTask);

            asyncService.processTask(normalTask);
            asyncService.processTask(highTask);

            latch.await(10, TimeUnit.SECONDS);
            assertThat(executionOrder).isNotEmpty();
        }

        @Test
        @DisplayName("任务完成 - 发布事件通知")
        void executeAsyncTask_PublishesEvent() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);

            doAnswer(invocation -> {
                latch.countDown();
                return null;
            }).when(eventPublisher).publishEvent(any(DnsResolveCompletedEvent.class));

            when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
                DnsAsyncTask task = invocation.getArgument(0);
                taskStore.put(task.getTaskId(), task);
                return 1;
            });

            AsyncResolveRequest request = buildRequest();
            request.setCallbackType("event");
            request.setEventName("CUSTOM_DNS_EVENT");

            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            assertNotNull(task);

            asyncService.processTask(task);

            boolean eventPublished = latch.await(10, TimeUnit.SECONDS);
            assertTrue(eventPublished, "事件应被发布");

            verify(eventPublisher, atLeastOnce()).publishEvent(any(DnsResolveCompletedEvent.class));
        }

        @Test
        @DisplayName("任务取消 - 成功")
        void cancelTask_Success() {
            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest()).block();
            assertNotNull(task);

            Mono<Boolean> result = asyncService.cancelTask(task.getTaskId());

            StepVerifier.create(result)
                    .expectNext(true)
                    .verifyComplete();

            DnsAsyncTask cancelled = taskStore.get(task.getTaskId());
            assertEquals("CANCELLED", cancelled.getStatus());
        }

        @Test
        @DisplayName("取消已完成任务 - 失败")
        void cancelTask_AlreadyCompleted_Failure() {
            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest()).block();
            assertNotNull(task);
            task.setStatus("COMPLETED");

            when(asyncTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);

            Mono<Boolean> result = asyncService.cancelTask(task.getTaskId());

            StepVerifier.create(result)
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================== 任务管理测试 ====================

    @Nested
    @DisplayName("任务管理测试")
    class TaskManagementTests {

        @Test
        @DisplayName("获取任务详情 - 成功")
        void getTaskDetail_Success() {
            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest()).block();
            assertNotNull(task);

            when(asyncTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);

            Mono<DnsAsyncTask> result = asyncService.getTaskDetail(task.getTaskId());

            StepVerifier.create(result)
                    .expectNextMatches(t -> {
                        assertThat(t.getTaskId()).isEqualTo(task.getTaskId());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("按状态查询任务 - 成功")
        void getTasksByStatus_Success() {
            for (int i = 0; i < 3; i++) {
                asyncService.submitAsyncResolve(buildRequest()).block();
            }

            Mono<List<DnsAsyncTask>> result = asyncService.getTasksByStatus("PENDING");

            StepVerifier.create(result)
                    .expectNextMatches(tasks -> {
                        assertThat(tasks).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("按域名查询任务 - 成功")
        void getTasksByDomain_Success() {
            AsyncResolveRequest request1 = buildRequest();
            request1.setDomain("search.example.com");
            asyncService.submitAsyncResolve(request1).block();

            AsyncResolveRequest request2 = buildRequest();
            request2.setDomain("other.example.com");
            asyncService.submitAsyncResolve(request2).block();

            Mono<List<DnsAsyncTask>> result = asyncService.getTasksByDomain("search.example.com");

            StepVerifier.create(result)
                    .expectNextMatches(tasks -> {
                        assertThat(tasks).hasSize(1);
                        assertThat(tasks.get(0).getDomain()).isEqualTo("search.example.com");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取任务统计 - 成功")
        void getTaskStats_Success() {
            asyncService.submitAsyncResolve(buildRequest()).block();
            asyncService.submitAsyncResolve(buildRequest()).block();

            Mono<Map<String, Object>> result = asyncService.getTaskStats();

            StepVerifier.create(result)
                    .expectNextMatches(stats -> {
                        assertThat((Long) stats.get("totalTasks")).isEqualTo(2);
                        assertThat((Long) stats.get("pendingTasks")).isEqualTo(2);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("清理已完成任务 - 成功")
        void cleanupCompletedTasks_Success() {
            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest()).block();
            assertNotNull(task);
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now().minusDays(2));

            when(asyncTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));

            Mono<Integer> result = asyncService.cleanupCompletedTasks(1);

            StepVerifier.create(result)
                    .expectNext(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("流式获取任务状态 - 成功")
        void streamTaskStatus_Success() {
            DnsAsyncTask task = asyncService.submitAsyncResolve(buildRequest()).block();
            assertNotNull(task);
            task.setStatus("PROCESSING");

            when(asyncTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);

            Flux<DnsAsyncTask> flux = asyncService.streamTaskStatus(task.getTaskId())
                    .take(1)
                    .timeout(Duration.ofSeconds(5));

            StepVerifier.create(flux)
                    .expectNextMatches(t -> "PROCESSING".equals(t.getStatus()))
                    .verifyComplete();
        }
    }

    // ==================== 测试辅助方法 ====================

    private AsyncResolveRequest buildRequest() {
        AsyncResolveRequest request = new AsyncResolveRequest();
        request.setDomain("test-" + UUID.randomUUID() + ".example.com");
        request.setQueryType("A");
        request.setPriority("normal");
        request.setMaxRetries(3);
        request.setRequestedBy("test-user");
        request.setContext(Map.of("test-key", "test-value"));
        return request;
    }
}
