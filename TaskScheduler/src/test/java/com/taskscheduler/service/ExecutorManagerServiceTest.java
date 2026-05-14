package com.taskscheduler.service;

import com.taskscheduler.TestDataBuilder;
import com.taskscheduler.dto.RegisterExecutorRequest;
import com.taskscheduler.entity.Executor;
import com.taskscheduler.exception.NoAvailableExecutorException;
import com.taskscheduler.repository.ExecutorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("执行器管理模块 - 负载均衡测试")
class ExecutorManagerServiceTest {

    @Mock
    private ExecutorRepository executorRepository;

    @InjectMocks
    private ExecutorManagerService executorManagerService;

    private List<Executor> testExecutors;

    @BeforeEach
    void setUp() {
        testExecutors = TestDataBuilder.createExecutorsWithMixedLoad(5);
    }

    @Test
    @DisplayName("测试负载均衡选择算法 - 选择负载最低的执行器")
    void testSelectExecutorWithLowestLoad() {
        List<Executor> executors = Arrays.asList(
                TestDataBuilder.createExecutor("executor_1", 5, 10),
                TestDataBuilder.createExecutor("executor_2", 2, 10),
                TestDataBuilder.createExecutor("executor_3", 8, 10)
        );

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenReturn(executors);
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor selected = executorManagerService.selectExecutor("batch");

        assertEquals("executor_2", selected.getExecutorId());
        assertEquals(3, selected.getCurrentLoad());
    }

    @Test
    @DisplayName("测试负载计量 - 每次选择后负载增加")
    void testLoadIncrementOnSelection() {
        Executor executor = TestDataBuilder.createExecutor("executor_1", 0, 10);
        List<Executor> executors = Collections.singletonList(executor);

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenReturn(executors);
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            executor.setCurrentLoad(saved.getCurrentLoad());
            return saved;
        });

        Executor selected1 = executorManagerService.selectExecutor("batch");
        assertEquals(1, selected1.getCurrentLoad());

        Executor selected2 = executorManagerService.selectExecutor("batch");
        assertEquals(2, selected2.getCurrentLoad());

        verify(executorRepository, times(2)).save(any(Executor.class));
    }

    @Test
    @DisplayName("测试执行器负载释放 - 释放后负载减少")
    void testLoadDecrementOnRelease() {
        Executor executor = TestDataBuilder.createExecutor("executor_1", 5, 10);

        when(executorRepository.findByExecutorId("executor_1")).thenReturn(Optional.of(executor));
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            executor.setCurrentLoad(saved.getCurrentLoad());
            return saved;
        });

        executorManagerService.releaseExecutor("executor_1");
        assertEquals(4, executor.getCurrentLoad());

        executorManagerService.releaseExecutor("executor_1");
        assertEquals(3, executor.getCurrentLoad());
    }

    @Test
    @DisplayName("测试执行器负载边界 - 负载不为负")
    void testLoadNotNegative() {
        Executor executor = TestDataBuilder.createExecutor("executor_1", 0, 10);

        when(executorRepository.findByExecutorId("executor_1")).thenReturn(Optional.of(executor));
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            executor.setCurrentLoad(saved.getCurrentLoad());
            return saved;
        });

        executorManagerService.releaseExecutor("executor_1");
        assertEquals(0, executor.getCurrentLoad());
    }

    @Test
    @DisplayName("测试无可用执行器 - 抛出异常")
    void testNoAvailableExecutorThrowsException() {
        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenReturn(Collections.emptyList());

        assertThrows(NoAvailableExecutorException.class, () -> {
            executorManagerService.selectExecutor("batch");
        });
    }

    @Test
    @DisplayName("测试容量已满的执行器被排除")
    void testFullExecutorExcluded() {
        List<Executor> executors = Arrays.asList(
                TestDataBuilder.createExecutor("executor_1", 10, 10),
                TestDataBuilder.createExecutor("executor_2", 3, 10)
        );

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenReturn(
                Collections.singletonList(executors.get(1))
        );
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor selected = executorManagerService.selectExecutor("batch");
        assertEquals("executor_2", selected.getExecutorId());
    }

    @Test
    @DisplayName("测试离线执行器被排除")
    void testOfflineExecutorExcluded() {
        List<Executor> executors = Arrays.asList(
                TestDataBuilder.createOfflineExecutor("executor_1"),
                TestDataBuilder.createExecutor("executor_2", 5, 10)
        );

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenReturn(
                Collections.singletonList(executors.get(1))
        );
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor selected = executorManagerService.selectExecutor("batch");
        assertEquals("executor_2", selected.getExecutorId());
    }

    @Test
    @DisplayName("测试任务类型兼容性 - 只选择匹配类型的执行器")
    void testTaskTypeCompatibility() {
        Executor batchExecutor = TestDataBuilder.createExecutor("batch_executor", 5, 10);
        batchExecutor.setTaskType("batch");

        Executor etlExecutor = TestDataBuilder.createExecutor("etl_executor", 2, 10);
        etlExecutor.setTaskType("etl");

        when(executorRepository.findAvailableExecutorsForTaskType("batch")).thenReturn(
                Collections.singletonList(batchExecutor)
        );
        when(executorRepository.findAvailableExecutorsForTaskType("etl")).thenReturn(
                Collections.singletonList(etlExecutor)
        );
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor selectedBatch = executorManagerService.selectExecutor("batch");
        assertEquals("batch_executor", selectedBatch.getExecutorId());

        Executor selectedEtl = executorManagerService.selectExecutor("etl");
        assertEquals("etl_executor", selectedEtl.getExecutorId());
    }

    @Test
    @DisplayName("测试多执行器场景下的任务分配均衡性")
    void testBalancedTaskDistribution() throws Exception {
        int executorCount = 5;
        int taskCount = 100;

        Map<String, AtomicInteger> taskDistribution = new HashMap<>();
        List<Executor> executors = new ArrayList<>();

        for (int i = 0; i < executorCount; i++) {
            String executorId = "executor_" + i;
            Executor executor = TestDataBuilder.createExecutor(executorId, 0, 100);
            executors.add(executor);
            taskDistribution.put(executorId, new AtomicInteger(0));
        }

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenAnswer(invocation -> {
            return new ArrayList<>(executors);
        });

        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            for (Executor e : executors) {
                if (e.getExecutorId().equals(saved.getExecutorId())) {
                    e.setCurrentLoad(saved.getCurrentLoad());
                }
            }
            return saved;
        });

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            futures.add(executorService.submit(() -> {
                Executor selected = executorManagerService.selectExecutor("batch");
                taskDistribution.get(selected.getExecutorId()).incrementAndGet();
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        executorService.shutdown();

        int expectedPerExecutor = taskCount / executorCount;
        int tolerance = (int) (expectedPerExecutor * 0.2);

        for (Map.Entry<String, AtomicInteger> entry : taskDistribution.entrySet()) {
            int actual = entry.getValue().get();
            assertTrue(
                    Math.abs(actual - expectedPerExecutor) <= tolerance,
                    String.format("执行器 %s 分配的任务数 %d 超出预期范围 %d±%d",
                            entry.getKey(), actual, expectedPerExecutor, tolerance)
            );
        }
    }

    @Test
    @DisplayName("测试执行器注册 - 新执行器注册成功")
    void testRegisterNewExecutor() {
        RegisterExecutorRequest request = new RegisterExecutorRequest();
        request.setExecutorId("new_executor");
        request.setExecutorName("新执行器");
        request.setExecutorAddress("192.168.1.100:9000");
        request.setMaxCapacity(20);
        request.setTaskType("batch");

        when(executorRepository.findByExecutorId("new_executor")).thenReturn(Optional.empty());
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor registered = executorManagerService.registerExecutor(request);

        assertEquals("new_executor", registered.getExecutorId());
        assertEquals("online", registered.getExecutorStatus());
        assertEquals(0, registered.getCurrentLoad());
    }

    @Test
    @DisplayName("测试执行器重新注册 - 更新状态为在线")
    void testReRegisterExecutor() {
        Executor existingExecutor = TestDataBuilder.createOfflineExecutor("existing_executor");

        RegisterExecutorRequest request = new RegisterExecutorRequest();
        request.setExecutorId("existing_executor");
        request.setExecutorName("更新后的执行器");

        when(executorRepository.findByExecutorId("existing_executor")).thenReturn(Optional.of(existingExecutor));
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            existingExecutor.setExecutorStatus(saved.getExecutorStatus());
            existingExecutor.setExecutorName(saved.getExecutorName());
            existingExecutor.setLastActive(saved.getLastActive());
            return saved;
        });

        Executor updated = executorManagerService.registerExecutor(request);

        assertEquals("online", updated.getExecutorStatus());
        assertEquals("更新后的执行器", updated.getExecutorName());
    }

    @Test
    @DisplayName("测试心跳机制 - 活跃状态保持")
    void testHeartbeatMaintainsOnlineStatus() {
        Executor executor = TestDataBuilder.createExecutor("heartbeat_executor");

        when(executorRepository.findByExecutorId("heartbeat_executor")).thenReturn(Optional.of(executor));
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            executor.setLastActive(saved.getLastActive());
            return saved;
        });

        executorManagerService.heartbeat("heartbeat_executor");

        verify(executorRepository).save(any(Executor.class));
    }

    @Test
    @DisplayName("测试执行器健康检查 - 超时执行器标记为离线")
    void testHealthCheckMarksTimeoutExecutorsOffline() {
        Executor activeExecutor = TestDataBuilder.createExecutor("active_executor");
        Executor timeoutExecutor = TestDataBuilder.createExecutor("timeout_executor");
        timeoutExecutor.setLastActive(java.time.LocalDateTime.now().minusMinutes(5));

        List<Executor> onlineExecutors = Arrays.asList(activeExecutor, timeoutExecutor);

        when(executorRepository.findByExecutorStatus("online")).thenReturn(onlineExecutors);
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        executorManagerService.checkAndMarkOfflineExecutors(60);

        verify(executorRepository, times(1)).save(any(Executor.class));
    }

    @Test
    @DisplayName("测试执行器注销 - 状态变为离线")
    void testUnregisterExecutor() {
        Executor executor = TestDataBuilder.createExecutor("to_unregister");

        when(executorRepository.findByExecutorId("to_unregister")).thenReturn(Optional.of(executor));
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            Executor saved = invocation.getArgument(0);
            executor.setExecutorStatus(saved.getExecutorStatus());
            return saved;
        });

        executorManagerService.unregisterExecutor("to_unregister");

        assertEquals("offline", executor.getExecutorStatus());
    }

    @Test
    @DisplayName("测试获取在线执行器列表")
    void testGetOnlineExecutors() {
        List<Executor> onlineExecutors = TestDataBuilder.createExecutors(3);
        when(executorRepository.findByExecutorStatus("online")).thenReturn(onlineExecutors);

        List<Executor> result = executorManagerService.getOnlineExecutors();

        assertEquals(3, result.size());
        verify(executorRepository).findByExecutorStatus("online");
    }

    @Test
    @DisplayName("测试获取所有执行器")
    void testGetAllExecutors() {
        List<Executor> allExecutors = TestDataBuilder.createExecutors(5);
        when(executorRepository.findAll()).thenReturn(allExecutors);

        List<Executor> result = executorManagerService.getAllExecutors();

        assertEquals(5, result.size());
        verify(executorRepository).findAll();
    }

    @Test
    @DisplayName("测试负载均衡在并发场景下的线程安全")
    void testLoadBalancingThreadSafety() throws Exception {
        int threadCount = 20;
        int operationsPerThread = 100;

        Executor executor = TestDataBuilder.createExecutor("concurrent_executor", 0, 10000);
        List<Executor> executors = new ArrayList<>();
        executors.add(executor);

        when(executorRepository.findAvailableExecutorsForTaskType(anyString())).thenAnswer(invocation -> {
            return new ArrayList<>(executors);
        });
        when(executorRepository.save(any(Executor.class))).thenAnswer(invocation -> {
            synchronized (this) {
                Executor saved = invocation.getArgument(0);
                executor.setCurrentLoad(saved.getCurrentLoad());
                return saved;
            }
        });

        ExecutorService service = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * operationsPerThread);

        for (int i = 0; i < threadCount; i++) {
            for (int j = 0; j < operationsPerThread; j++) {
                service.submit(() -> {
                    try {
                        executorManagerService.selectExecutor("batch");
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);
        service.shutdown();

        assertEquals(threadCount * operationsPerThread, executor.getCurrentLoad());
    }
}
