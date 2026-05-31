package com.datapipeline.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TaskScheduler {

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final List<TaskStatusListener> listeners = new CopyOnWriteArrayList<>();

    public TaskScheduler(int threadPoolSize) {
        this.scheduler = Executors.newScheduledThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "task-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void registerListener(TaskStatusListener listener) {
        listeners.add(listener);
    }

    public ScheduledTask scheduleOneTime(String name, Runnable task, Duration delay) {
        return scheduleOneTime(name, task, delay, Collections.emptyMap());
    }

    public ScheduledTask scheduleOneTime(String name, Runnable task, Duration delay,
                                         Map<String, Object> metadata) {
        ScheduledTask scheduled = ScheduledTask.builder()
                .taskId(UUID.randomUUID().toString())
                .name(name)
                .type(ScheduledTask.Type.ONE_TIME)
                .task(task)
                .metadata(metadata)
                .status(ScheduledTask.Status.PENDING)
                .scheduledAt(Instant.now())
                .initialDelay(delay)
                .nextExecutionAt(Instant.now().plus(delay))
                .maxRetries(0)
                .build();

        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeTask(scheduled),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );

        tasks.put(scheduled.getTaskId(), scheduled);
        futures.put(scheduled.getTaskId(), future);
        notifyStatusChange(scheduled);
        log.info("One-time task scheduled: id={}, name={}, delay={}ms",
                scheduled.getTaskId(), name, delay.toMillis());
        return scheduled;
    }

    public ScheduledTask scheduleFixedRate(String name, Runnable task, Duration initialDelay,
                                           Duration period, Map<String, Object> metadata) {
        ScheduledTask scheduled = ScheduledTask.builder()
                .taskId(UUID.randomUUID().toString())
                .name(name)
                .type(ScheduledTask.Type.FIXED_RATE)
                .task(task)
                .metadata(metadata)
                .status(ScheduledTask.Status.PENDING)
                .scheduledAt(Instant.now())
                .initialDelay(initialDelay)
                .period(period)
                .nextExecutionAt(Instant.now().plus(initialDelay))
                .maxRetries(0)
                .build();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeTask(scheduled),
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
        );

        tasks.put(scheduled.getTaskId(), scheduled);
        futures.put(scheduled.getTaskId(), future);
        notifyStatusChange(scheduled);
        log.info("Fixed-rate task scheduled: id={}, name={}, period={}ms",
                scheduled.getTaskId(), name, period.toMillis());
        return scheduled;
    }

    public ScheduledTask scheduleWithFixedDelay(String name, Runnable task, Duration initialDelay,
                                                Duration delay, Map<String, Object> metadata) {
        ScheduledTask scheduled = ScheduledTask.builder()
                .taskId(UUID.randomUUID().toString())
                .name(name)
                .type(ScheduledTask.Type.FIXED_DELAY)
                .task(task)
                .metadata(metadata)
                .status(ScheduledTask.Status.PENDING)
                .scheduledAt(Instant.now())
                .initialDelay(initialDelay)
                .period(delay)
                .nextExecutionAt(Instant.now().plus(initialDelay))
                .maxRetries(0)
                .build();

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> executeTask(scheduled),
                initialDelay.toMillis(),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );

        tasks.put(scheduled.getTaskId(), scheduled);
        futures.put(scheduled.getTaskId(), future);
        notifyStatusChange(scheduled);
        log.info("Fixed-delay task scheduled: id={}, name={}, delay={}ms",
                scheduled.getTaskId(), name, delay.toMillis());
        return scheduled;
    }

    public boolean cancelTask(String taskId) {
        ScheduledTask task = tasks.get(taskId);
        ScheduledFuture<?> future = futures.get(taskId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            if (task != null) {
                task.markCancelled();
                notifyStatusChange(task);
            }
            futures.remove(taskId);
            log.info("Task cancelled: id={}", taskId);
            return cancelled;
        }
        return false;
    }

    public Optional<ScheduledTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<ScheduledTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public List<ScheduledTask> getTasksByStatus(ScheduledTask.Status status) {
        return tasks.values().stream()
                .filter(t -> t.getStatus() == status)
                .collect(java.util.stream.Collectors.toList());
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Task scheduler shutdown completed");
    }

    private void executeTask(ScheduledTask task) {
        task.markRunning();
        notifyStatusChange(task);
        log.debug("Task started: id={}, name={}", task.getTaskId(), task.getName());

        try {
            if (task.getTimeout() != null) {
                executeWithTimeout(task);
            } else {
                task.getTask().run();
            }
            task.markCompleted();
            log.debug("Task completed: id={}, name={}, duration={}ms",
                    task.getTaskId(), task.getName(), task.getDurationMs());
        } catch (Exception e) {
            task.markFailed(e.getMessage(), e);
            log.error("Task failed: id={}, name={}", task.getTaskId(), task.getName(), e);
        }

        if (task.getType() == ScheduledTask.Type.FIXED_RATE || task.getType() == ScheduledTask.Type.FIXED_DELAY) {
            task.setNextExecutionAt(Instant.now().plus(task.getPeriod()));
        }

        notifyStatusChange(task);
    }

    private void executeWithTimeout(ScheduledTask task) throws Exception {
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Exception[] exception = new Exception[1];

        Thread executionThread = new Thread(() -> {
            try {
                task.getTask().run();
            } catch (Exception e) {
                exception[0] = e;
            } finally {
                completed.set(true);
                latch.countDown();
            }
        });

        executionThread.start();
        boolean finished = latch.await(task.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

        if (!finished) {
            executionThread.interrupt();
            task.markTimedOut();
            throw new TimeoutException("Task execution timed out: " + task.getTimeout());
        }

        if (exception[0] != null) {
            throw exception[0];
        }
    }

    private void notifyStatusChange(ScheduledTask task) {
        for (TaskStatusListener listener : listeners) {
            try {
                listener.onStatusChange(task);
            } catch (Exception e) {
                log.error("Task status listener failed", e);
            }
        }
    }

    @FunctionalInterface
    public interface TaskStatusListener {
        void onStatusChange(ScheduledTask task);
    }

}
