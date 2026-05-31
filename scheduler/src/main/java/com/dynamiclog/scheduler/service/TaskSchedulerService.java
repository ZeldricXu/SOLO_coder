package com.dynamiclog.scheduler.service;

import com.dynamiclog.common.entity.Task;
import com.dynamiclog.common.entity.TaskRun;
import com.dynamiclog.common.enums.TaskStatus;
import com.dynamiclog.common.exception.BusinessException;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.persistence.mapper.TaskMapper;
import com.dynamiclog.persistence.mapper.TaskRunMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskSchedulerService {

    private final TaskMapper taskMapper;
    private final TaskRunMapper taskRunMapper;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final MeterRegistry meterRegistry;

    private final Map<String, ScheduledFuture<?>> scheduledTasks;
    private final Map<String, Set<String>> dependencyGraph;
    private final Map<String, Set<String>> reverseDependencyGraph;

    private final ResourcePool resourcePool;
    private final WorkerPool workerPool;
    private final Sinks.Many<TaskEvent> eventSink;

    private Counter taskSubmittedCounter;
    private Counter taskCompletedCounter;
    private Counter taskFailedCounter;
    private Timer taskExecutionTimer;

    public TaskSchedulerService(
            TaskMapper taskMapper,
            TaskRunMapper taskRunMapper,
            ThreadPoolTaskScheduler taskScheduler,
            MeterRegistry meterRegistry) {
        this.taskMapper = taskMapper;
        this.taskRunMapper = taskRunMapper;
        this.taskScheduler = taskScheduler;
        this.meterRegistry = meterRegistry;
        this.scheduledTasks = new ConcurrentHashMap<>();
        this.dependencyGraph = new ConcurrentHashMap<>();
        this.reverseDependencyGraph = new ConcurrentHashMap<>();
        this.resourcePool = new ResourcePool(meterRegistry);
        this.workerPool = new WorkerPool(10);
        this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
    }

    @PostConstruct
    public void initMetrics() {
        this.taskSubmittedCounter = Counter.builder("scheduler.tasks.submitted")
                .description("Number of tasks submitted")
                .register(meterRegistry);
        this.taskCompletedCounter = Counter.builder("scheduler.tasks.completed")
                .description("Number of tasks completed")
                .register(meterRegistry);
        this.taskFailedCounter = Counter.builder("scheduler.tasks.failed")
                .description("Number of tasks failed")
                .register(meterRegistry);
        this.taskExecutionTimer = Timer.builder("scheduler.tasks.execution.duration")
                .description("Task execution duration")
                .register(meterRegistry);

        Gauge.builder("scheduler.pool.active.workers", workerPool, WorkerPool::getActiveWorkers)
                .description("Active workers count")
                .register(meterRegistry);
        Gauge.builder("scheduler.pool.idle.workers", workerPool, WorkerPool::getIdleWorkers)
                .description("Idle workers count")
                .register(meterRegistry);
        Gauge.builder("scheduler.pool.queue.size", workerPool, WorkerPool::getQueueSize)
                .description("Worker queue size")
                .register(meterRegistry);
    }

    public Mono<Task> createTask(Task task) {
        return Mono.fromCallable(() -> {
            task.setId(IdGenerator.generateId("task"));
            task.setStatus(TaskStatus.PENDING);
            task.setProgress(0.0);
            task.setRetryCount(0);
            taskMapper.insert(task);
            buildDependencyGraph(task);
            taskSubmittedCounter.increment();
            emitEvent(TaskEvent.created(task));
            log.info("Task created: id={}, name={}", task.getId(), task.getName());
            return task;
        });
    }

    public Mono<Task> scheduleTask(String taskId) {
        return Mono.fromCallable(() -> {
            Task task = getTaskOrThrow(taskId);
            validateTaskSchedulable(task);

            Worker worker = workerPool.borrowWorker(taskId);
            if (worker == null) {
                throw new BusinessException(429, "No available workers, please retry later");
            }

            try {
                ScheduledFuture<?> future = scheduleTaskInternal(task, worker);
                scheduledTasks.put(taskId, future);
                updateTaskStatus(task, TaskStatus.SCHEDULED);
                emitEvent(TaskEvent.scheduled(task));
                log.info("Task scheduled: id={}, name={}, worker={}", taskId, task.getName(), worker.getId());
                return task;
            } catch (Exception e) {
                workerPool.returnWorker(worker);
                throw e;
            }
        });
    }

    public Mono<Task> getTask(String taskId) {
        return Mono.fromCallable(() -> getTaskOrThrow(taskId));
    }

    public Flux<Task> getTasksByStatus(TaskStatus status) {
        return Mono.fromCallable(() -> taskMapper.findByStatus(status))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<TaskRun> getTaskRun(String runId) {
        return Mono.fromCallable(() -> {
            TaskRun run = taskRunMapper.findByRunId(runId);
            if (run == null) {
                throw new ResourceNotFoundException("TaskRun", runId);
            }
            return run;
        });
    }

    public Mono<Void> cancelTask(String taskId) {
        return Mono.fromRunnable(() -> {
            cancelScheduledFuture(taskId);
            Task task = taskMapper.selectById(taskId);
            if (task != null) {
                updateTaskStatus(task, TaskStatus.CANCELLED);
                workerPool.releaseWorkerByTask(taskId);
                emitEvent(TaskEvent.cancelled(task));
            }
            log.info("Task cancelled: id={}", taskId);
        });
    }

    public Mono<Void> executeTaskNow(String taskId) {
        return Mono.fromRunnable(() -> {
            Task task = getTaskOrThrow(taskId);
            Worker worker = workerPool.borrowWorker(taskId);
            if (worker == null) {
                throw new BusinessException(429, "No available workers");
            }
            try {
                executeTask(task, worker);
            } finally {
                workerPool.returnWorker(worker);
            }
        });
    }

    public Mono<List<Task>> getTaskDependencies(String taskId) {
        return Mono.fromCallable(() -> {
            Task task = getTaskOrThrow(taskId);
            if (task.getDependencies() == null || task.getDependencies().isEmpty()) {
                return Collections.emptyList();
            }
            return task.getDependencies().stream()
                    .map(taskMapper::selectById)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        });
    }

    public Flux<TaskRun> getTaskRuns(String taskId, int limit) {
        return Mono.fromCallable(() -> taskRunMapper.findByTaskIdLimit(taskId, limit))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<ResourceLease> acquireResource(String resourceType, long timeoutMs) {
        return Mono.fromCallable(() -> resourcePool.acquire(resourceType, timeoutMs))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> releaseResource(ResourceLease lease) {
        return Mono.fromRunnable(() -> resourcePool.release(lease));
    }

    public Mono<Map<String, Object>> getPoolStats() {
        return Mono.fromCallable(() -> Map.of(
                "workerPool", Map.of(
                        "totalWorkers", workerPool.getTotalWorkers(),
                        "activeWorkers", workerPool.getActiveWorkers(),
                        "idleWorkers", workerPool.getIdleWorkers(),
                        "queueSize", workerPool.getQueueSize()
                ),
                "resourcePool", resourcePool.getStats(),
                "scheduledTasks", scheduledTasks.size()
        ));
    }

    public Flux<TaskEvent> listenEvents() {
        return eventSink.asFlux();
    }

    private Task getTaskOrThrow(String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("Task", taskId);
        }
        return task;
    }

    private void validateTaskSchedulable(Task task) {
        if (!canSchedule(task)) {
            throw new BusinessException(400, "Task dependencies not met");
        }
    }

    private void cancelScheduledFuture(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void updateTaskStatus(Task task, TaskStatus status) {
        task.setStatus(status);
        taskMapper.updateById(task);
    }

    private ScheduledFuture<?> scheduleTaskInternal(Task task, Worker worker) {
        Runnable taskRunner = buildTaskRunner(task, worker);
        return scheduleWithStrategy(task, taskRunner);
    }

    private Runnable buildTaskRunner(Task task, Worker worker) {
        return () -> {
            try {
                if (!canExecute(task)) {
                    log.warn("Task skipped due to unmet dependencies: id={}", task.getId());
                    workerPool.returnWorker(worker);
                    return;
                }
                executeTask(task, worker);
            } catch (Exception e) {
                log.error("Task execution failed: id={}", task.getId(), e);
                handleTaskFailure(task, e);
                workerPool.returnWorker(worker);
            }
        };
    }

    private ScheduledFuture<?> scheduleWithStrategy(Task task, Runnable taskRunner) {
        if (task.getCronExpression() != null) {
            return taskScheduler.schedule(taskRunner, new CronTrigger(task.getCronExpression()));
        }
        if (task.getFixedRateMs() != null) {
            return taskScheduler.scheduleAtFixedRate(taskRunner, task.getFixedRateMs());
        }
        if (task.getFixedDelayMs() != null) {
            return taskScheduler.scheduleWithFixedDelay(taskRunner, task.getFixedDelayMs());
        }
        taskScheduler.execute(taskRunner);
        return null;
    }

    private void executeTask(Task task, Worker worker) {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        TaskRun run = createTaskRun(task);
        updateTaskForExecution(task, run.getRunId());
        worker.setCurrentTaskId(task.getId());

        try {
            log.info("Task execution started: id={}, runId={}, worker={}", task.getId(), run.getRunId(), worker.getId());
            Object result = executeTaskHandler(task);
            completeTask(task, run, result);
            triggerDependentTasks(task);
            log.info("Task execution completed: id={}, runId={}", task.getId(), run.getRunId());
        } catch (Exception e) {
            handleTaskFailure(task, run, e);
        } finally {
            timerSample.stop(taskExecutionTimer);
            worker.setCurrentTaskId(null);
            workerPool.returnWorker(worker);
        }
    }

    private TaskRun createTaskRun(Task task) {
        String runId = IdGenerator.generateId("run");
        TaskRun run = new TaskRun();
        run.setId(IdGenerator.generateId("tr"));
        run.setRunId(runId);
        run.setTaskId(task.getId());
        run.setStatus(TaskStatus.RUNNING);
        run.setProgress(0.0);
        run.setStartedAt(LocalDateTime.now());
        taskRunMapper.insert(run);
        return run;
    }

    private void updateTaskForExecution(Task task, String runId) {
        task.setStatus(TaskStatus.RUNNING);
        task.setRunId(runId);
        task.setProgress(0.0);
        taskMapper.updateById(task);
    }

    private Object executeTaskHandler(Task task) throws Exception {
        if (task.getHandlerClass() == null) {
            return null;
        }
        Class<?> handlerClazz = Class.forName(task.getHandlerClass());
        Object handler = handlerClazz.getDeclaredConstructor().newInstance();
        if (handler instanceof Function) {
            return ((Function<String, ?>) handler).apply(task.getPayload());
        }
        return null;
    }

    private void completeTask(Task task, TaskRun run, Object result) {
        run.setProgress(1.0);
        run.setStatus(TaskStatus.COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setDurationMs(java.time.Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
        run.setResult(result != null ? result.toString() : null);
        taskRunMapper.updateById(run);

        updateTaskStatus(task, TaskStatus.COMPLETED);
        task.setProgress(1.0);
        taskMapper.updateById(task);

        taskCompletedCounter.increment();
        emitEvent(TaskEvent.completed(task));
    }

    private void handleTaskFailure(Task task, TaskRun run, Exception e) {
        run.setStatus(TaskStatus.FAILED);
        run.setErrorDetail(e.getMessage());
        run.setCompletedAt(LocalDateTime.now());
        taskRunMapper.updateById(run);
        handleTaskFailure(task, e);
    }

    private void handleTaskFailure(Task task, Exception e) {
        task.setRetryCount(task.getRetryCount() + 1);
        if (task.getRetryCount() < task.getMaxRetries()) {
            task.setStatus(TaskStatus.PENDING);
            log.info("Task will be retried: id={}, retryCount={}", task.getId(), task.getRetryCount());
        } else {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorDetail(e.getMessage());
            taskFailedCounter.increment();
            log.error("Task failed after max retries: id={}", task.getId());
        }
        taskMapper.updateById(task);
        emitEvent(TaskEvent.failed(task));
    }

    private boolean canSchedule(Task task) {
        if (task.getDependencies() == null || task.getDependencies().isEmpty()) {
            return true;
        }
        return task.getDependencies().stream()
                .map(taskMapper::selectById)
                .allMatch(dep -> dep != null && dep.getStatus() == TaskStatus.COMPLETED);
    }

    private boolean canExecute(Task task) {
        return canSchedule(task);
    }

    private void triggerDependentTasks(Task task) {
        Set<String> dependents = reverseDependencyGraph.getOrDefault(task.getId(), Collections.emptySet());
        dependents.stream()
                .map(taskMapper::selectById)
                .filter(Objects::nonNull)
                .filter(dep -> dep.getStatus() == TaskStatus.PENDING)
                .filter(this::canSchedule)
                .forEach(dep -> scheduleTask(dep.getId()).subscribe());
    }

    private void buildDependencyGraph(Task task) {
        if (task.getDependencies() != null) {
            dependencyGraph.put(task.getId(), new HashSet<>(task.getDependencies()));
            task.getDependencies().forEach(depId ->
                    reverseDependencyGraph.computeIfAbsent(depId, k -> ConcurrentHashMap.newKeySet())
                            .add(task.getId()));
        }
    }

    private void emitEvent(TaskEvent event) {
        eventSink.tryEmitNext(event);
    }

    public static class Worker {
        @Getter
        private final String id;
        @Getter
        private volatile String currentTaskId;
        @Getter
        private volatile long lastUsedTime;

        public Worker(String id) {
            this.id = id;
            this.lastUsedTime = System.currentTimeMillis();
        }

        public void setCurrentTaskId(String taskId) {
            this.currentTaskId = taskId;
            if (taskId != null) {
                this.lastUsedTime = System.currentTimeMillis();
            }
        }

        public boolean isIdle() {
            return currentTaskId == null;
        }
    }

    public static class WorkerPool {
        private final BlockingQueue<Worker> idleWorkers;
        private final Set<Worker> activeWorkers;
        private final Map<String, Worker> taskWorkerMap;
        private final int poolSize;
        private final AtomicInteger workerCounter;

        public WorkerPool(int poolSize) {
            this.poolSize = poolSize;
            this.idleWorkers = new LinkedBlockingQueue<>(poolSize);
            this.activeWorkers = ConcurrentHashMap.newKeySet();
            this.taskWorkerMap = new ConcurrentHashMap<>();
            this.workerCounter = new AtomicInteger(0);
            initializeWorkers();
        }

        private void initializeWorkers() {
            for (int i = 0; i < poolSize; i++) {
                idleWorkers.offer(new Worker("worker-" + workerCounter.incrementAndGet()));
            }
        }

        public Worker borrowWorker(String taskId) {
            Worker worker = idleWorkers.poll();
            if (worker != null) {
                activeWorkers.add(worker);
                taskWorkerMap.put(taskId, worker);
            }
            return worker;
        }

        public void returnWorker(Worker worker) {
            if (worker == null) return;
            activeWorkers.remove(worker);
            taskWorkerMap.values().remove(worker);
            worker.setCurrentTaskId(null);
            idleWorkers.offer(worker);
        }

        public void releaseWorkerByTask(String taskId) {
            Worker worker = taskWorkerMap.remove(taskId);
            if (worker != null) {
                returnWorker(worker);
            }
        }

        public int getTotalWorkers() { return poolSize; }
        public int getActiveWorkers() { return activeWorkers.size(); }
        public int getIdleWorkers() { return idleWorkers.size(); }
        public int getQueueSize() { return idleWorkers.size(); }
    }

    public static class ResourceLease {
        @Getter private final String leaseId;
        @Getter private final String resourceType;
        @Getter private final long acquiredAt;
        private volatile boolean released;

        public ResourceLease(String resourceType) {
            this.leaseId = IdGenerator.generateId("lease");
            this.resourceType = resourceType;
            this.acquiredAt = System.currentTimeMillis();
        }

        public boolean isReleased() { return released; }
        public void markReleased() { this.released = true; }
    }

    public static class ResourcePool {
        private final Map<String, Semaphore> resourceSemaphores;
        private final Map<String, Set<ResourceLease>> activeLeases;
        private final MeterRegistry meterRegistry;

        public ResourcePool(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            this.resourceSemaphores = new ConcurrentHashMap<>();
            this.activeLeases = new ConcurrentHashMap<>();
            registerResource("cpu", 10);
            registerResource("io", 20);
            registerResource("memory", 50);
        }

        public void registerResource(String type, int maxPermits) {
            resourceSemaphores.put(type, new Semaphore(maxPermits, true));
            activeLeases.put(type, ConcurrentHashMap.newKeySet());
            Gauge.builder("scheduler.resource.available", () -> resourceSemaphores.get(type).availablePermits())
                    .tag("type", type)
                    .description("Available resources")
                    .register(meterRegistry);
        }

        public ResourceLease acquire(String resourceType, long timeoutMs) throws InterruptedException, TimeoutException {
            Semaphore semaphore = resourceSemaphores.get(resourceType);
            if (semaphore == null) {
                throw new IllegalArgumentException("Unknown resource type: " + resourceType);
            }
            if (!semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Failed to acquire resource: " + resourceType);
            }
            ResourceLease lease = new ResourceLease(resourceType);
            activeLeases.get(resourceType).add(lease);
            return lease;
        }

        public void release(ResourceLease lease) {
            if (lease == null || lease.isReleased()) return;
            Semaphore semaphore = resourceSemaphores.get(lease.getResourceType());
            if (semaphore != null) {
                semaphore.release();
            }
            Set<ResourceLease> leases = activeLeases.get(lease.getResourceType());
            if (leases != null) {
                leases.remove(lease);
            }
            lease.markReleased();
        }

        public Map<String, Object> getStats() {
            Map<String, Object> stats = new HashMap<>();
            resourceSemaphores.forEach((type, semaphore) -> {
                int available = semaphore.availablePermits();
                int waiting = semaphore.getQueueLength();
                int activeLeaseCount = activeLeases.getOrDefault(type, Collections.emptySet()).size();
                stats.put(type, Map.of(
                        "total", available + waiting,
                        "available", available,
                        "waiting", waiting,
                        "activeLeases", activeLeaseCount
                ));
            });
            return stats;
        }
    }

    public record TaskEvent(String type, String taskId, String taskName) {
        public static TaskEvent created(Task task) {
            return new TaskEvent("task.created", task.getId(), task.getName());
        }
        public static TaskEvent scheduled(Task task) {
            return new TaskEvent("task.scheduled", task.getId(), task.getName());
        }
        public static TaskEvent completed(Task task) {
            return new TaskEvent("task.completed", task.getId(), task.getName());
        }
        public static TaskEvent failed(Task task) {
            return new TaskEvent("task.failed", task.getId(), task.getName());
        }
        public static TaskEvent cancelled(Task task) {
            return new TaskEvent("task.cancelled", task.getId(), task.getName());
        }
    }
}
