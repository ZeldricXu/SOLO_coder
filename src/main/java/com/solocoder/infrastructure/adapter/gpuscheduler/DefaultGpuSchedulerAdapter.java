package com.solocoder.infrastructure.adapter.gpuscheduler;

import com.solocoder.domain.model.RunInstance;
import com.solocoder.domain.port.GpuSchedulerPort;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class DefaultGpuSchedulerAdapter implements GpuSchedulerPort {

    @Value("${gpu.scheduler.node-count:4}")
    private int nodeCount;

    @Value("${gpu.scheduler.gpus-per-node:8}")
    private int gpusPerNode;

    @Value("${gpu.scheduler.preemption-enabled:true}")
    private boolean preemptionEnabled;

    private final Map<String, GpuTask> taskRegistry = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<GpuTask> waitingQueue = new PriorityBlockingQueue<>(
            100, Comparator.comparingInt((GpuTask t) -> t.priority).reversed()
    );
    private final Map<String, GpuTask> runningTasks = new ConcurrentHashMap<>();
    private final Queue<GpuTask> completedTasks = new ConcurrentLinkedQueue<>();

    private final int[] gpuAllocations;
    private final ExecutorService schedulerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean schedulerRunning = new AtomicBoolean(true);
    private final Sinks.Many<RunInstance> taskStatusSink = Sinks.many().multicast().onBackpressureBuffer();

    @PostConstruct
    public void init() {
        gpuAllocations = new int[nodeCount * gpusPerNode];
        schedulerExecutor.submit(this::schedulerLoop);
    }

    @Override
    public Mono<RunInstance> submitTask(String taskName, int priority, int gpuRequirement,
                                         Map<String, Object> parameters, Runnable task) {
        return Mono.fromCallable(() -> {
            String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");
            GpuTask gpuTask = new GpuTask(
                    taskId, taskName, priority, gpuRequirement, parameters, task,
                    Instant.now(), "pending", 0.0
            );
            taskRegistry.put(taskId, gpuTask);
            waitingQueue.add(gpuTask);

            RunInstance runInstance = toRunInstance(gpuTask);
            taskStatusSink.tryEmitNext(runInstance);
            return runInstance;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> cancelTask(String taskId) {
        return Mono.fromRunnable(() -> {
            GpuTask task = taskRegistry.get(taskId);
            if (task != null) {
                waitingQueue.remove(task);
                runningTasks.remove(taskId);
                task.status = "cancelled";
                taskStatusSink.tryEmitNext(toRunInstance(task));
                releaseGpus(task);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<RunInstance> getTaskStatus(String taskId) {
        return Mono.fromCallable(() -> {
            GpuTask task = taskRegistry.get(taskId);
            return task != null ? toRunInstance(task) : null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<RunInstance> listTasks(String status) {
        return Flux.fromIterable(taskRegistry.values())
                .filter(task -> status == null || status.equals(task.status))
                .map(this::toRunInstance);
    }

    @Override
    public Mono<Void> preemptTask(String taskId) {
        return Mono.fromRunnable(() -> {
            if (!preemptionEnabled) {
                throw new IllegalStateException("Preemption is not enabled");
            }
            GpuTask task = runningTasks.get(taskId);
            if (task != null) {
                task.status = "preempted";
                taskStatusSink.tryEmitNext(toRunInstance(task));
                releaseGpus(task);
                waitingQueue.add(task);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Map<String, Object> getClusterStatus() {
        int totalGpus = gpuAllocations.length;
        int usedGpus = (int) Arrays.stream(gpuAllocations).filter(gpu -> gpu != 0).count();
        int availableGpus = totalGpus - usedGpus;

        Map<String, Object> status = new HashMap<>();
        status.put("totalNodes", nodeCount);
        status.put("gpusPerNode", gpusPerNode);
        status.put("totalGpus", totalGpus);
        status.put("usedGpus", usedGpus);
        status.put("availableGpus", availableGpus);
        status.put("preemptionEnabled", preemptionEnabled);
        status.put("waitingTasks", waitingQueue.size());
        status.put("runningTasks", runningTasks.size());
        status.put("completedTasks", completedTasks.size());

        List<Map<String, Object>> nodeStatuses = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            Map<String, Object> nodeStatus = new HashMap<>();
            nodeStatus.put("nodeId", "node_" + i);
            int nodeUsed = 0;
            for (int j = 0; j < gpusPerNode; j++) {
                if (gpuAllocations[i * gpusPerNode + j] != 0) {
                    nodeUsed++;
                }
            }
            nodeStatus.put("usedGpus", nodeUsed);
            nodeStatus.put("availableGpus", gpusPerNode - nodeUsed);
            nodeStatuses.add(nodeStatus);
        }
        status.put("nodes", nodeStatuses);

        return status;
    }

    @Override
    public Mono<Void> adjustTaskPriority(String taskId, int newPriority) {
        return Mono.fromRunnable(() -> {
            GpuTask task = taskRegistry.get(taskId);
            if (task != null && "pending".equals(task.status)) {
                waitingQueue.remove(task);
                task.priority = newPriority;
                waitingQueue.add(task);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void schedulerLoop() {
        while (schedulerRunning.get()) {
            try {
                GpuTask task = waitingQueue.peek();
                if (task != null) {
                    int allocationIndex = findAvailableGpus(task.gpuRequirement);
                    if (allocationIndex >= 0) {
                        waitingQueue.poll();
                        allocateGpus(task, allocationIndex);
                        runningTasks.put(task.taskId, task);
                        task.status = "running";
                        taskStatusSink.tryEmitNext(toRunInstance(task));
                        executeTask(task);
                    }
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log error
            }
        }
    }

    private int findAvailableGpus(int required) {
        int consecutive = 0;
        int startIndex = 0;
        for (int i = 0; i < gpuAllocations.length; i++) {
            if (gpuAllocations[i] == 0) {
                consecutive++;
                if (consecutive == required) {
                    return startIndex;
                }
            } else {
                consecutive = 0;
                startIndex = i + 1;
            }
        }

        if (preemptionEnabled && !waitingQueue.isEmpty()) {
            GpuTask nextTask = waitingQueue.peek();
            for (GpuTask runningTask : runningTasks.values()) {
                if (runningTask.priority < nextTask.priority && runningTask.gpuRequirement >= required) {
                    preemptTask(runningTask.taskId).block();
                    return findAvailableGpus(required);
                }
            }
        }

        return -1;
    }

    private void allocateGpus(GpuTask task, int startIndex) {
        task.allocatedGpuStart = startIndex;
        for (int i = 0; i < task.gpuRequirement; i++) {
            gpuAllocations[startIndex + i] = task.taskId.hashCode();
        }
    }

    private void releaseGpus(GpuTask task) {
        if (task.allocatedGpuStart >= 0) {
            for (int i = 0; i < task.gpuRequirement; i++) {
                gpuAllocations[task.allocatedGpuStart + i] = 0;
            }
            task.allocatedGpuStart = -1;
        }
    }

    private void executeTask(GpuTask task) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                task.progress = 0.5;
                taskStatusSink.tryEmitNext(toRunInstance(task));

                task.task.run();

                task.progress = 1.0;
                task.status = "completed";
                task.completedAt = Instant.now();
            } catch (Exception e) {
                task.status = "failed";
                task.errorDetail = e.getMessage();
            } finally {
                releaseGpus(task);
                runningTasks.remove(task.taskId);
                completedTasks.add(task);
                taskStatusSink.tryEmitNext(toRunInstance(task));
            }
        });
    }

    private RunInstance toRunInstance(GpuTask task) {
        return RunInstance.builder()
                .runId(task.taskId)
                .entityId(task.taskName)
                .phase(task.status)
                .progress(task.progress)
                .startedAt(task.startedAt)
                .completedAt(task.completedAt)
                .errorDetail(task.errorDetail)
                .build();
    }

    @Data
    private static class GpuTask {
        private final String taskId;
        private final String taskName;
        private int priority;
        private final int gpuRequirement;
        private final Map<String, Object> parameters;
        private final Runnable task;
        private final Instant startedAt;
        private String status;
        private double progress;
        private Instant completedAt;
        private String errorDetail;
        private int allocatedGpuStart = -1;

        GpuTask(String taskId, String taskName, int priority, int gpuRequirement,
                Map<String, Object> parameters, Runnable task, Instant startedAt,
                String status, double progress) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.priority = priority;
            this.gpuRequirement = gpuRequirement;
            this.parameters = parameters;
            this.task = task;
            this.startedAt = startedAt;
            this.status = status;
            this.progress = progress;
        }
    }

    private static class PriorityBlockingQueue<E> {
        private final Queue<E> queue;
        private final Comparator<? super E> comparator;

        public PriorityBlockingQueue(int initialCapacity, Comparator<? super E> comparator) {
            this.queue = new PriorityQueue<>(initialCapacity, comparator);
            this.comparator = comparator;
        }

        public synchronized boolean add(E e) {
            return queue.add(e);
        }

        public synchronized E peek() {
            return queue.peek();
        }

        public synchronized E poll() {
            return queue.poll();
        }

        public synchronized boolean remove(E e) {
            return queue.remove(e);
        }

        public synchronized int size() {
            return queue.size();
        }

        public synchronized boolean isEmpty() {
            return queue.isEmpty();
        }
    }
}
