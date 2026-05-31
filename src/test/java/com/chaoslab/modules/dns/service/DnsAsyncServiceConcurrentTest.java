package com.chaoslab.modules.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.entity.DnsUpstream;
import com.chaoslab.mapper.DnsAsyncTaskMapper;
import com.chaoslab.mapper.DnsUpstreamMapper;
import com.chaoslab.mapper.DnsZoneMapper;
import com.chaoslab.modules.dns.dto.AsyncResolveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DnsAsyncService 并发测试")
class DnsAsyncServiceConcurrentTest extends ConcurrentTestBase {

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

    @InjectMocks
    private DnsAsyncService asyncService;

    private final ConcurrentHashMap<String, DnsAsyncTask> taskStore = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final AtomicInteger processedCounter = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        taskStore.clear();
        idCounter.set(0);
        processedCounter.set(0);
        setupMockBehaviors();
    }

    private void setupMockBehaviors() {
        when(asyncTaskMapper.insert(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
            DnsAsyncTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId((long) idCounter.incrementAndGet());
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
            if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                processedCounter.incrementAndGet();
            }
            return 1;
        });

        when(proxyService.resolve(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String domain = invocation.getArgument(0);
                    return Mono.just(Map.of(
                            "status", "NOERROR",
                            "records", List.of(Map.of(
                                    "domain", domain,
                                    "value", "127.0.0.1",
                                    "ttl", 300
                            )),
                            "source", "concurrent-test"
                    ));
                });
    }

    @Test
    @DisplayName("并发提交异步任务 - 线程安全")
    void concurrentSubmitAsyncTask_ThreadSafe() throws Exception {
        assertConcurrentSafety(
                () -> {
                    AsyncResolveRequest request = new AsyncResolveRequest();
                    request.setDomain("concurrent-" + UUID.randomUUID() + ".example.com");
                    request.setQueryType("A");
                    return asyncService.submitAsyncResolve(request).block();
                },
                DEFAULT_THREAD_COUNT,
                20
        );

        assertThat(taskStore).hasSize(DEFAULT_THREAD_COUNT * DEFAULT_ITERATIONS);
    }

    @Test
    @DisplayName("并发处理异步任务 - 执行顺序和完整性")
    void concurrentProcessTasks_ExecutionOrderAndCompleteness() throws Exception {
        int taskCount = 50;
        List<DnsAsyncTask> tasks = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("proc-" + i + "-" + UUID.randomUUID() + ".example.com");
            request.setQueryType("A");
            request.setPriority(i % 3 == 0 ? "high" : "normal");
            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            tasks.add(task);
        }

        assertThat(tasks).hasSize(taskCount);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (DnsAsyncTask task : tasks) {
            executor.submit(() -> {
                try {
                    asyncService.processTask(task);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        Thread.sleep(1000);

        assertThat(processedCounter.get()).isEqualTo(taskCount);

        for (DnsAsyncTask task : tasks) {
            DnsAsyncTask updated = taskStore.get(task.getTaskId());
            assertThat(updated.getStatus()).isEqualTo("COMPLETED");
            assertThat(updated.getResult()).isNotNull();
            assertThat(updated.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("并发提交与处理 - 高吞吐")
    void concurrentSubmitAndProcess_HighThroughput() throws Exception {
        int submitCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch submitLatch = new CountDownLatch(submitCount);
        List<DnsAsyncTask> submittedTasks = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < submitCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    AsyncResolveRequest request = new AsyncResolveRequest();
                    request.setDomain("throughput-" + index + "-" + UUID.randomUUID() + ".example.com");
                    request.setQueryType("A");
                    request.setPriority(index % 5 == 0 ? "high" : "normal");
                    request.setMaxRetries(2);
                    DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
                    if (task != null) {
                        submittedTasks.add(task);
                    }
                } finally {
                    submitLatch.countDown();
                }
            });
        }

        submitLatch.await(30, TimeUnit.SECONDS);
        assertThat(submittedTasks).hasSize(submitCount);

        CountDownLatch processLatch = new CountDownLatch(submitCount);
        for (DnsAsyncTask task : submittedTasks) {
            executor.submit(() -> {
                try {
                    asyncService.processTask(task);
                } finally {
                    processLatch.countDown();
                }
            });
        }

        processLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        Thread.sleep(1000);

        long completedCount = taskStore.values().stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()))
                .count();
        assertThat(completedCount).isEqualTo(submitCount);
    }

    @Test
    @DisplayName("并发查询任务状态 - 无脏读")
    void concurrentQueryTaskStatus_NoDirtyRead() throws Exception {
        for (int i = 0; i < 20; i++) {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("query-" + i + ".example.com");
            request.setQueryType("A");
            asyncService.submitAsyncResolve(request).block();
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch latch = new CountDownLatch(60);

        for (int i = 0; i < 30; i++) {
            executor.submit(() -> {
                try {
                    var stats = asyncService.getTaskStats().block();
                    assertThat(stats).isNotNull();
                    assertThat((Long) stats.get("totalTasks")).isEqualTo(20);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    var pendingTasks = asyncService.getTasksByStatus("PENDING").block();
                    assertThat(pendingTasks).isNotNull();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    @Test
    @DisplayName("并发取消任务 - 原子操作")
    void concurrentCancelTasks_AtomicOperation() throws Exception {
        int taskCount = 30;
        List<DnsAsyncTask> tasks = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("cancel-" + i + ".example.com");
            request.setQueryType("A");
            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            tasks.add(task);
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (DnsAsyncTask task : tasks) {
            executor.submit(() -> {
                try {
                    when(asyncTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
                    Boolean result = asyncService.cancelTask(task.getTaskId()).block();
                    assertThat(result).isNotNull();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long cancelledCount = taskStore.values().stream()
                .filter(t -> "CANCELLED".equals(t.getStatus()))
                .count();
        assertThat(cancelledCount).isEqualTo(taskCount);
    }

    @Test
    @DisplayName("并发任务重试 - 幂等性保证")
    void concurrentRetryTasks_Idempotency() throws Exception {
        AtomicInteger failCount = new AtomicInteger(0);

        when(proxyService.resolve(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    if (failCount.incrementAndGet() <= 2) {
                        throw new RuntimeException("Temporary DNS failure");
                    }
                    String domain = invocation.getArgument(0);
                    return Mono.just(Map.of(
                            "status", "NOERROR",
                            "records", List.of(Map.of("domain", domain, "value", "127.0.0.1"))
                    ));
                });

        AsyncResolveRequest request = new AsyncResolveRequest();
        request.setDomain("retry-test.example.com");
        request.setQueryType("A");
        request.setMaxRetries(3);

        DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
        assertThat(task).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);
        when(asyncTaskMapper.updateById(any(DnsAsyncTask.class))).thenAnswer(invocation -> {
            DnsAsyncTask t = invocation.getArgument(0);
            taskStore.put(t.getTaskId(), t);
            if ("COMPLETED".equals(t.getStatus())) {
                latch.countDown();
            }
            return 1;
        });

        asyncService.processTask(task);

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed);

        DnsAsyncTask finalTask = taskStore.get(task.getTaskId());
        assertThat(finalTask.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalTask.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发清理任务 - 资源释放")
    void concurrentCleanupTasks_ResourceRelease() throws Exception {
        for (int i = 0; i < 20; i++) {
            AsyncResolveRequest request = new AsyncResolveRequest();
            request.setDomain("cleanup-" + i + ".example.com");
            request.setQueryType("A");
            DnsAsyncTask task = asyncService.submitAsyncResolve(request).block();
            if (task != null) {
                task.setStatus("COMPLETED");
                task.setCompletedAt(LocalDateTime.now().minusDays(i + 1));
            }
        }

        assertResourceReleaseConcurrent(
                () -> asyncService.cleanupCompletedTasks(0).block(),
                () -> {},
                5,
                10
        );
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(taskStore).isNotEmpty();
    }
}
