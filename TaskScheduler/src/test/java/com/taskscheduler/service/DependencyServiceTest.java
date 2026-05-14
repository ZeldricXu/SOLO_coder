package com.taskscheduler.service;

import com.taskscheduler.TestDataBuilder;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.exception.DependencyNotCompletedException;
import com.taskscheduler.repository.DependencyRepository;
import com.taskscheduler.repository.ExecuteRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("依赖管理模块测试")
class DependencyServiceTest {

    @Mock
    private DependencyRepository dependencyRepository;

    @Mock
    private ExecuteRecordRepository executeRecordRepository;

    @InjectMocks
    private DependencyService dependencyService;

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("测试无依赖的任务 - 检查通过")
    void testNoDependenciesPass() {
        String taskId = "no_dep_task";

        when(dependencyRepository.findDependenciesByTaskId(taskId)).thenReturn(Collections.emptyList());

        boolean result = dependencyService.checkDependenciesCompleted(taskId);

        assertTrue(result);
        verify(dependencyRepository).findDependenciesByTaskId(taskId);
        verify(executeRecordRepository, never()).findByTaskIdOrderByExecuteTimeDesc(anyString());
    }

    @Test
    @DisplayName("测试依赖任务成功 - 当前任务可以执行")
    void testDependencySuccessAllowsCurrentTask() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord successRecord = TestDataBuilder.createExecuteRecord("exec_1", dependencyTaskId, "success");

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(successRecord));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试依赖任务失败 - 当前任务被阻塞")
    void testDependencyFailureBlocksCurrentTask() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord failedRecord = TestDataBuilder.createFailedExecuteRecord("exec_1", dependencyTaskId);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(failedRecord));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试依赖任务运行中 - 当前任务被阻塞")
    void testDependencyRunningBlocksCurrentTask() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord runningRecord = TestDataBuilder.createRunningExecuteRecord("exec_1", dependencyTaskId);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(runningRecord));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试依赖任务未执行 - 当前任务被阻塞")
    void testDependencyNeverExecutedBlocksCurrentTask() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.emptyList());

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试多个依赖 - 全部成功才通过")
    void testMultipleDependenciesAllSuccess() {
        String currentTaskId = "current_task";
        String dep1 = "dep_task_1";
        String dep2 = "dep_task_2";
        String dep3 = "dep_task_3";

        List<String> dependencies = Arrays.asList(dep1, dep2, dep3);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep1))
                .thenReturn(Collections.singletonList(TestDataBuilder.createExecuteRecord("exec_dep1", dep1, "success")));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep2))
                .thenReturn(Collections.singletonList(TestDataBuilder.createExecuteRecord("exec_dep2", dep2, "success")));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep3))
                .thenReturn(Collections.singletonList(TestDataBuilder.createExecuteRecord("exec_dep3", dep3, "success")));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试多个依赖 - 任一失败则阻塞")
    void testMultipleDependenciesOneFailureBlocks() {
        String currentTaskId = "current_task";
        String dep1 = "dep_task_1";
        String dep2 = "dep_task_2";
        String dep3 = "dep_task_3";

        List<String> dependencies = Arrays.asList(dep1, dep2, dep3);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep1))
                .thenReturn(Collections.singletonList(TestDataBuilder.createExecuteRecord("exec_dep1", dep1, "success")));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep2))
                .thenReturn(Collections.singletonList(TestDataBuilder.createFailedExecuteRecord("exec_dep2", dep2)));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dep3))
                .thenReturn(Collections.singletonList(TestDataBuilder.createExecuteRecord("exec_dep3", dep3, "success")));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试依赖检查抛出异常 - 依赖失败时抛出")
    void testValidateDependenciesThrowsOnFailure() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord failedRecord = TestDataBuilder.createFailedExecuteRecord("exec_1", dependencyTaskId);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(failedRecord));

        assertThrows(DependencyNotCompletedException.class, () -> {
            dependencyService.validateDependenciesOrThrow(currentTaskId);
        });
    }

    @Test
    @DisplayName("测试依赖检查不抛出异常 - 依赖成功时不抛出")
    void testValidateDependenciesNoExceptionOnSuccess() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord successRecord = TestDataBuilder.createExecuteRecord("exec_1", dependencyTaskId, "success");

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(successRecord));

        assertDoesNotThrow(() -> {
            dependencyService.validateDependenciesOrThrow(currentTaskId);
        });
    }

    @Test
    @DisplayName("测试依赖链检测 - A -> B -> C")
    void testDependencyChainDetection() {
        String taskA = "task_a";
        String taskB = "task_b";
        String taskC = "task_c";

        when(dependencyRepository.findDependentTasks(taskA)).thenReturn(Collections.singletonList(taskB));
        when(dependencyRepository.findDependentTasks(taskB)).thenReturn(Collections.singletonList(taskC));
        when(dependencyRepository.findDependentTasks(taskC)).thenReturn(Collections.emptyList());

        List<String> dependentsOfA = dependencyService.getDependentTasks(taskA);
        List<String> dependentsOfB = dependencyService.getDependentTasks(taskB);
        List<String> dependentsOfC = dependencyService.getDependentTasks(taskC);

        assertEquals(1, dependentsOfA.size());
        assertEquals(taskB, dependentsOfA.get(0));
        assertEquals(1, dependentsOfB.size());
        assertEquals(taskC, dependentsOfB.get(0));
        assertTrue(dependentsOfC.isEmpty());
    }

    @Test
    @DisplayName("测试循环依赖检测 - A -> B -> A")
    void testCircularDependencyDetection() {
        String taskA = "task_a";
        String taskB = "task_b";

        when(dependencyRepository.findDependentTasks(taskA)).thenReturn(Collections.singletonList(taskB));
        when(dependencyRepository.findDependentTasks(taskB)).thenReturn(Collections.singletonList(taskA));

        List<String> dependentsOfA = dependencyService.getDependentTasks(taskA);
        List<String> dependentsOfB = dependencyService.getDependentTasks(taskB);

        assertEquals(1, dependentsOfA.size());
        assertEquals(taskB, dependentsOfA.get(0));
        assertEquals(1, dependentsOfB.size());
        assertEquals(taskA, dependentsOfB.get(0));
    }

    @Test
    @DisplayName("测试依赖超时 - 依赖任务执行超时时")
    void testDependencyTimeoutHandling() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);
        ExecuteRecord timeoutRecord = TestDataBuilder.createFailedExecuteRecord("exec_timeout", dependencyTaskId);
        timeoutRecord.setExecuteResult("Task execution timeout after 300 seconds");

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(Collections.singletonList(timeoutRecord));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试依赖重试成功 - 最终依赖成功后当前任务可执行")
    void testDependencyRetrySuccess() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);

        List<ExecuteRecord> recordsWithRetry = Arrays.asList(
                TestDataBuilder.createExecuteRecord("exec_success", dependencyTaskId, "success"),
                TestDataBuilder.createFailedExecuteRecord("exec_fail_2", dependencyTaskId),
                TestDataBuilder.createFailedExecuteRecord("exec_fail_1", dependencyTaskId)
        );

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(recordsWithRetry);

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试并发依赖检查")
    void testConcurrentDependencyCheck() throws Exception {
        int threadCount = 10;
        int checkCount = 100;
        String taskId = "concurrent_dep_task";

        List<String> dependencies = Collections.singletonList("dep_task");
        ExecuteRecord successRecord = TestDataBuilder.createExecuteRecord("exec_dep", "dep_task", "success");

        when(dependencyRepository.findDependenciesByTaskId(taskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc("dep_task"))
                .thenReturn(Collections.singletonList(successRecord));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(checkCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < checkCount; i++) {
            executorService.submit(() -> {
                try {
                    boolean result = dependencyService.checkDependenciesCompleted(taskId);
                    if (result) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(checkCount, successCount.get());
        assertEquals(0, errorCount.get());
    }

    @Test
    @DisplayName("测试获取任务依赖列表")
    void testGetTaskDependencies() {
        String taskId = "task_with_deps";
        List<com.taskscheduler.entity.Dependency> dependencies = TestDataBuilder.createDependencyChain(
                "task_a", "task_with_deps", "task_c");

        when(dependencyRepository.findByTaskId(taskId)).thenReturn(dependencies);

        List<com.taskscheduler.entity.Dependency> result = dependencyService.getTaskDependencies(taskId);

        assertEquals(dependencies.size(), result.size());
    }

    @Test
    @DisplayName("测试添加依赖关系")
    void testAddDependency() {
        String taskId = "task_a";
        String dependsOn = "task_b";

        when(dependencyRepository.existsByTaskIdAndDependsOn(taskId, dependsOn)).thenReturn(false);
        when(dependencyRepository.save(any(com.taskscheduler.entity.Dependency.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        dependencyService.addDependency(taskId, dependsOn);

        verify(dependencyRepository).save(any(com.taskscheduler.entity.Dependency.class));
    }

    @Test
    @DisplayName("测试添加已存在的依赖 - 不重复添加")
    void testAddExistingDependencyNoDuplicate() {
        String taskId = "task_a";
        String dependsOn = "task_b";

        when(dependencyRepository.existsByTaskIdAndDependsOn(taskId, dependsOn)).thenReturn(true);

        dependencyService.addDependency(taskId, dependsOn);

        verify(dependencyRepository, never()).save(any(com.taskscheduler.entity.Dependency.class));
    }

    @Test
    @DisplayName("测试移除依赖关系")
    void testRemoveDependency() {
        String taskId = "task_a";
        String dependsOn = "task_b";

        List<com.taskscheduler.entity.Dependency> dependencies = new ArrayList<>();
        com.taskscheduler.entity.Dependency dep = TestDataBuilder.createDependency(taskId, dependsOn);
        dependencies.add(dep);

        when(dependencyRepository.findByTaskId(taskId)).thenReturn(dependencies);

        dependencyService.removeDependency(taskId, dependsOn);

        verify(dependencyRepository).delete(dep);
    }

    @Test
    @DisplayName("测试依赖任务最新执行结果优先")
    void testLatestExecutionTakesPriority() {
        String currentTaskId = "current_task";
        String dependencyTaskId = "dependency_task";

        List<String> dependencies = Collections.singletonList(dependencyTaskId);

        List<ExecuteRecord> executionHistory = Arrays.asList(
                TestDataBuilder.createExecuteRecord("exec_latest_success", dependencyTaskId, "success"),
                TestDataBuilder.createFailedExecuteRecord("exec_older_fail", dependencyTaskId)
        );

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(dependencyTaskId))
                .thenReturn(executionHistory);

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试复杂依赖场景 - 混合成功/失败/运行中")
    void testComplexDependencyScenario() {
        String currentTaskId = "complex_task";
        String successDep = "success_dep";
        String runningDep = "running_dep";
        String failedDep = "failed_dep";

        List<String> dependencies = Arrays.asList(successDep, runningDep, failedDep);

        when(dependencyRepository.findDependenciesByTaskId(currentTaskId)).thenReturn(dependencies);
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(successDep))
                .thenReturn(Collections.singletonList(
                        TestDataBuilder.createExecuteRecord("exec_success", successDep, "success")));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(runningDep))
                .thenReturn(Collections.singletonList(
                        TestDataBuilder.createRunningExecuteRecord("exec_running", runningDep)));
        when(executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(failedDep))
                .thenReturn(Collections.singletonList(
                        TestDataBuilder.createFailedExecuteRecord("exec_failed", failedDep)));

        boolean result = dependencyService.checkDependenciesCompleted(currentTaskId);

        assertFalse(result);
    }
}
