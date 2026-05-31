package com.orchestration.scheduler.engine;

import com.orchestration.common.context.RequestContext;
import com.orchestration.common.context.TenantContext;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.IdGenerator;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.persistence.entity.TaskInstance;
import com.orchestration.persistence.mapper.TaskInstanceMapper;
import com.orchestration.scheduler.graph.TaskGraph;
import com.orchestration.scheduler.graph.TaskNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutionEngine {

    private final TaskInstanceMapper taskInstanceMapper;
    private final Map<String, TaskHandler> taskHandlers = new ConcurrentHashMap<>();

    private final ExecutorService executorService = new ThreadPoolExecutor(
            10, 50, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public void registerHandler(String taskType, TaskHandler handler) {
        taskHandlers.put(taskType, handler);
    }

    @Async
    public CompletableFuture<Void> executeGraphAsync(TaskGraph graph, Map<Long, Map<String, Object>> inputDataMap) {
        return CompletableFuture.runAsync(() -> executeGraph(graph, inputDataMap), executorService);
    }

    public void executeGraph(TaskGraph graph, Map<Long, Map<String, Object>> inputDataMap) {
        if (graph.hasCycle()) {
            throw new BusinessException("任务图中存在循环依赖，无法执行");
        }

        List<TaskNode> sortedNodes = graph.topologicalSort();
        Map<Long, CompletableFuture<TaskInstance>> futureMap = new ConcurrentHashMap<>();
        Map<Long, TaskInstance> resultMap = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        for (TaskNode node : sortedNodes) {
            CompletableFuture<TaskInstance> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> depsOutput = new HashMap<>();
                    for (TaskNode dep : node.getDependencies()) {
                        TaskInstance depInstance = futureMap.get(dep.getTaskId()).get();
                        if (!"success".equals(depInstance.getStatus())) {
                            throw new BusinessException("依赖任务执行失败: " + dep.getTaskName());
                        }
                        if (depInstance.getOutputData() != null) {
                            Map<String, Object> depOutput = JsonUtil.fromJson(depInstance.getOutputData(), Map.class);
                            depsOutput.putAll(depOutput);
                        }
                    }

                    Map<String, Object> input = new HashMap<>();
                    if (inputDataMap.get(node.getTaskId()) != null) {
                        input.putAll(inputDataMap.get(node.getTaskId()));
                    }
                    input.put("dependencyOutput", depsOutput);

                    TaskInstance instance = executeTask(node, input);
                    resultMap.put(node.getTaskId(), instance);
                    successCount.incrementAndGet();
                    return instance;
                } catch (Exception e) {
                    log.error("任务执行失败: {}", node.getTaskName(), e);
                    failedCount.incrementAndGet();
                    TaskInstance failedInstance = markTaskFailed(node.getTaskId(), e.getMessage());
                    resultMap.put(node.getTaskId(), failedInstance);
                    return failedInstance;
                }
            }, executorService);

            for (TaskNode dep : node.getDependencies()) {
                future = future.thenCombine(futureMap.get(dep.getTaskId()), (inst, depInst) -> inst);
            }

            futureMap.put(node.getTaskId(), future);
        }

        CompletableFuture.allOf(futureMap.values().toArray(new CompletableFuture[0])).join();
        log.info("任务图执行完成, 成功: {}, 失败: {}", successCount.get(), failedCount.get());
    }

    private TaskInstance executeTask(TaskNode node, Map<String, Object> inputData) {
        TaskInstance instance = createTaskInstance(node, inputData);

        try {
            instance.setStatus("running");
            instance.setPhase("executing");
            instance.setProgress(new BigDecimal("0.1"));
            instance.setStartedAt(LocalDateTime.now());
            taskInstanceMapper.updateById(instance);

            TaskHandler handler = taskHandlers.get(node.getTaskType());
            Map<String, Object> output;
            if (handler != null) {
                output = handler.handle(inputData);
            } else {
                output = executeDefaultTask(inputData);
            }

            instance.setStatus("success");
            instance.setPhase("completed");
            instance.setProgress(new BigDecimal("1.0"));
            instance.setOutputData(JsonUtil.toJson(output));
            instance.setCompletedAt(LocalDateTime.now());
            taskInstanceMapper.updateById(instance);

            return instance;
        } catch (Exception e) {
            instance.setStatus("failed");
            instance.setErrorDetail(e.getMessage());
            instance.setCompletedAt(LocalDateTime.now());
            taskInstanceMapper.updateById(instance);
            throw new BusinessException("任务执行失败: " + e.getMessage(), e);
        }
    }

    private TaskInstance createTaskInstance(TaskNode node, Map<String, Object> inputData) {
        TaskInstance instance = new TaskInstance();
        instance.setTaskId(node.getTaskId());
        instance.setInstanceNo(IdGenerator.generateId("inst"));
        instance.setStatus("pending");
        instance.setPhase("initializing");
        instance.setProgress(new BigDecimal("0"));
        instance.setInputData(JsonUtil.toJson(inputData));
        instance.setTenantId(TenantContext.getTenantId());
        taskInstanceMapper.insert(instance);
        return instance;
    }

    private TaskInstance markTaskFailed(Long taskId, String errorMessage) {
        TaskInstance instance = new TaskInstance();
        instance.setTaskId(taskId);
        instance.setInstanceNo(IdGenerator.generateId("inst"));
        instance.setStatus("failed");
        instance.setErrorDetail(errorMessage);
        instance.setProgress(new BigDecimal("0"));
        instance.setTenantId(TenantContext.getTenantId());
        taskInstanceMapper.insert(instance);
        return instance;
    }

    private Map<String, Object> executeDefaultTask(Map<String, Object> inputData) {
        Map<String, Object> output = new HashMap<>();
        output.put("processed", true);
        output.put("processedAt", System.currentTimeMillis());
        output.put("traceId", RequestContext.get().getTraceId());
        return output;
    }

    public interface TaskHandler {
        Map<String, Object> handle(Map<String, Object> inputData);
    }
}
