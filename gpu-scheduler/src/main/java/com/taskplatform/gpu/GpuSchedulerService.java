package com.taskplatform.gpu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.GpuResource;
import com.taskplatform.persistence.mapper.GpuResourceMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuSchedulerService {

    private final GpuResourceMapper gpuResourceMapper;
    private final PriorityBlockingQueue<GpuTask> taskQueue = new PriorityBlockingQueue<>(
            1000, Comparator.comparingInt((GpuTask t) -> t.priority.getLevel()).reversed()
    );
    private final Map<String, GpuTask> runningTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduling = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public GpuResource registerGpu(GpuResource gpu) {
        gpu.setGpuId(IdGenerator.generateGpuId());
        gpu.setStatus("IDLE");
        gpu.setLastHeartbeat(LocalDateTime.now());
        gpuResourceMapper.insert(gpu);
        log.info("Registered GPU: {} on node {}", gpu.getGpuId(), gpu.getNodeName());
        return gpu;
    }

    public List<GpuResource> listGpus(String nodeName, String status) {
        LambdaQueryWrapper<GpuResource> query = new LambdaQueryWrapper<>();
        if (nodeName != null) {
            query.eq(GpuResource::getNodeName, nodeName);
        }
        if (status != null) {
            query.eq(GpuResource::getStatus, status);
        }
        return gpuResourceMapper.selectList(query);
    }

    public GpuResource getGpu(String gpuId) {
        GpuResource gpu = gpuResourceMapper.selectOne(
                new LambdaQueryWrapper<GpuResource>().eq(GpuResource::getGpuId, gpuId)
        );
        if (gpu == null) {
            throw new BusinessException(404, "GPU_NOT_FOUND", "GPU not found: " + gpuId);
        }
        return gpu;
    }

    public void heartbeat(String gpuId, int usedMemoryMb, double utilization) {
        GpuResource gpu = getGpu(gpuId);
        gpu.setUsedMemoryMb(usedMemoryMb);
        gpu.setGpuUtilization(utilization);
        gpu.setLastHeartbeat(LocalDateTime.now());
        gpuResourceMapper.updateById(gpu);
    }

    public String submitGpuTask(String taskId, TaskPriority priority, int requiredMemoryMb,
                                String modelName, Callable<Object> task) {
        GpuTask gpuTask = new GpuTask();
        gpuTask.taskId = taskId;
        gpuTask.priority = priority;
        gpuTask.requiredMemoryMb = requiredMemoryMb;
        gpuTask.modelName = modelName;
        gpuTask.task = task;
        gpuTask.submittedAt = LocalDateTime.now();

        taskQueue.offer(gpuTask);
        log.info("Submitted GPU task: {} with priority {}", taskId, priority);
        return taskId;
    }

    @Scheduled(fixedDelayString = "${gpu.scheduler.interval:1000}")
    public void scheduleTasks() {
        if (!scheduling.compareAndSet(false, true)) {
            return;
        }

        try {
            List<GpuResource> availableGpus = gpuResourceMapper.selectList(
                    new LambdaQueryWrapper<GpuResource>()
                            .eq(GpuResource::getStatus, "IDLE")
                            .or(w -> w.eq(GpuResource::getStatus, "RUNNING"))
            );

            while (!taskQueue.isEmpty()) {
                GpuTask task = taskQueue.peek();
                if (task == null) break;

                GpuResource assignedGpu = findBestGpu(availableGpus, task);
                if (assignedGpu == null) {
                    if (task.priority == TaskPriority.CRITICAL) {
                        assignedGpu = tryPreemptGpu(task, availableGpus);
                    }
                    if (assignedGpu == null) {
                        break;
                    }
                }

                taskQueue.poll();
                assignGpuToTask(assignedGpu, task);
            }
        } catch (Exception e) {
            log.error("GPU scheduling failed", e);
        } finally {
            scheduling.set(false);
        }
    }

    private GpuResource findBestGpu(List<GpuResource> gpus, GpuTask task) {
        return gpus.stream()
                .filter(g -> "IDLE".equals(g.getStatus()))
                .filter(g -> (g.getTotalMemoryMb() - g.getUsedMemoryMb()) >= task.requiredMemoryMb)
                .min(Comparator.comparingInt(g -> g.getTotalMemoryMb() - task.requiredMemoryMb))
                .orElse(null);
    }

    private GpuResource tryPreemptGpu(GpuTask highPriorityTask, List<GpuResource> gpus) {
        return gpus.stream()
                .filter(g -> "RUNNING".equals(g.getStatus()))
                .filter(g -> runningTasks.containsKey(g.getCurrentTaskId()))
                .filter(g -> {
                    GpuTask running = runningTasks.get(g.getCurrentTaskId());
                    return running.priority.getLevel() < highPriorityTask.priority.getLevel();
                })
                .min(Comparator.comparingInt(g -> {
                    GpuTask running = runningTasks.get(g.getCurrentTaskId());
                    return running.priority.getLevel();
                }))
                .map(gpu -> {
                    preemptGpu(gpu);
                    return gpu;
                })
                .orElse(null);
    }

    private void preemptGpu(GpuResource gpu) {
        String taskId = gpu.getCurrentTaskId();
        GpuTask running = runningTasks.remove(taskId);
        if (running != null && running.future != null) {
            running.future.cancel(true);
            log.info("Preempted GPU task: {} for higher priority task", taskId);
            taskQueue.offer(running);
        }

        gpu.setStatus("IDLE");
        gpu.setCurrentTaskId(null);
        gpu.setUsedMemoryMb(0);
        gpuResourceMapper.updateById(gpu);
    }

    private void assignGpuToTask(GpuResource gpu, GpuTask task) {
        gpu.setStatus("RUNNING");
        gpu.setCurrentTaskId(task.taskId);
        gpu.setUsedMemoryMb(task.requiredMemoryMb);
        gpuResourceMapper.updateById(gpu);

        task.assignedGpuId = gpu.getGpuId();
        task.startedAt = LocalDateTime.now();

        Future<?> future = executor.submit(() -> {
            try {
                log.info("Starting GPU task: {} on GPU {}", task.taskId, gpu.getGpuId());
                Object result = task.task.call();
                completeTask(task, result, null);
            } catch (Exception e) {
                log.error("GPU task failed: {}", task.taskId, e);
                completeTask(task, null, e);
            }
        });

        task.future = (Future<Object>) future;
        runningTasks.put(task.taskId, task);
    }

    private void completeTask(GpuTask task, Object result, Throwable error) {
        runningTasks.remove(task.taskId);
        task.completedAt = LocalDateTime.now();
        task.result = result;
        task.error = error;

        if (task.assignedGpuId != null) {
            try {
                GpuResource gpu = getGpu(task.assignedGpuId);
                gpu.setStatus("IDLE");
                gpu.setCurrentTaskId(null);
                gpu.setUsedMemoryMb(0);
                gpuResourceMapper.updateById(gpu);
            } catch (Exception e) {
                log.warn("Failed to release GPU: {}", task.assignedGpuId, e);
            }
        }

        log.info("Completed GPU task: {} in {}ms", task.taskId,
                java.time.Duration.between(task.startedAt, task.completedAt).toMillis());
    }

    @Scheduled(fixedDelayString = "${gpu.heartbeat.check.interval:30000}")
    public void checkStaleGpus() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<GpuResource> staleGpus = gpuResourceMapper.selectList(
                new LambdaQueryWrapper<GpuResource>()
                        .lt(GpuResource::getLastHeartbeat, threshold)
        );

        for (GpuResource gpu : staleGpus) {
            log.warn("GPU {} is stale, marking as unavailable", gpu.getGpuId());
            gpu.setStatus("UNAVAILABLE");
            if (gpu.getCurrentTaskId() != null) {
                GpuTask task = runningTasks.remove(gpu.getCurrentTaskId());
                if (task != null) {
                    taskQueue.offer(task);
                }
            }
            gpuResourceMapper.updateById(gpu);
        }
    }

    public Map<String, Object> getSchedulerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", taskQueue.size());
        status.put("runningTasks", runningTasks.size());
        status.put("totalGpus", gpuResourceMapper.selectCount(null));
        status.put("availableGpus", gpuResourceMapper.selectCount(
                new LambdaQueryWrapper<GpuResource>().eq(GpuResource::getStatus, "IDLE")
        ));

        List<Map<String, Object>> queueInfo = new ArrayList<>();
        for (GpuTask task : taskQueue) {
            Map<String, Object> info = new HashMap<>();
            info.put("taskId", task.taskId);
            info.put("priority", task.priority);
            info.put("requiredMemoryMb", task.requiredMemoryMb);
            info.put("waitTimeMs", java.time.Duration.between(
                    task.submittedAt, LocalDateTime.now()).toMillis());
            queueInfo.add(info);
        }
        status.put("queue", queueInfo);

        return status;
    }

    @Data
    public static class GpuTask {
        String taskId;
        TaskPriority priority;
        int requiredMemoryMb;
        String modelName;
        Callable<Object> task;
        LocalDateTime submittedAt;
        LocalDateTime startedAt;
        LocalDateTime completedAt;
        String assignedGpuId;
        Future<Object> future;
        Object result;
        Throwable error;
    }
}
