package com.datapipeline.fl.coordinator;

import com.datapipeline.fl.aggregation.GradientAggregator;
import com.datapipeline.fl.crypto.GradientEncryptor;
import com.datapipeline.fl.model.GlobalModel;
import com.datapipeline.fl.model.LocalGradient;
import com.datapipeline.fl.model.TrainingTask;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class FederatedCoordinator {

    private final Map<String, TrainingTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, GlobalModel> models = new ConcurrentHashMap<>();
    private final GradientAggregator aggregator;
    private final GradientEncryptor encryptor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> roundTimeouts = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);

    public FederatedCoordinator(GradientAggregator aggregator, GradientEncryptor encryptor) {
        this.aggregator = aggregator;
        this.encryptor = encryptor;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "fl-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    public TrainingTask createTask(String modelName, int totalRounds, int minParticipants,
                                   Duration roundTimeout, Map<String, Object> hyperparams) {
        String taskId = "task_" + taskCounter.incrementAndGet();
        GlobalModel initialModel = createInitialModel(modelName);

        TrainingTask task = TrainingTask.builder()
                .taskId(taskId)
                .modelName(modelName)
                .status(TrainingTask.Status.PENDING)
                .currentRound(0)
                .totalRounds(totalRounds)
                .minParticipants(minParticipants)
                .maxParticipants(minParticipants * 2)
                .roundTimeout(roundTimeout)
                .hyperparameters(new HashMap<>(hyperparams))
                .initialModel(initialModel)
                .createdAt(Instant.now())
                .build();

        tasks.put(taskId, task);
        models.put(modelName, initialModel);
        log.info("Training task created: id={}, model={}, rounds={}", taskId, modelName, totalRounds);
        return task;
    }

    public void dispatchTask(String taskId, Set<String> clientIds) {
        TrainingTask task = tasks.get(taskId);
        if (task == null) {
            log.warn("Task not found: id={}", taskId);
            return;
        }

        task.setAssignedClients(new HashSet<>(clientIds));
        task.setStatus(TrainingTask.Status.DISPATCHED);
        task.setStartedAt(Instant.now());
        task.setCurrentRound(1);

        scheduleRoundTimeout(taskId, task.getRoundTimeout());
        log.info("Task dispatched: id={}, clients={}", taskId, clientIds);
    }

    public boolean submitGradient(LocalGradient gradient) {
        TrainingTask task = tasks.get(gradient.getTaskId());
        if (task == null) {
            log.warn("Task not found for gradient: taskId={}", gradient.getTaskId());
            return false;
        }

        if (task.getStatus() != TrainingTask.Status.DISPATCHED && task.getStatus() != TrainingTask.Status.RUNNING) {
            log.warn("Gradient submitted for task not in running state: id={}, status={}",
                    gradient.getTaskId(), task.getStatus());
            return false;
        }

        task.getReceivedGradients().put(gradient.getClientId(), gradient);

        if (task.getStatus() == TrainingTask.Status.DISPATCHED) {
            task.setStatus(TrainingTask.Status.RUNNING);
        }

        log.debug("Gradient received: taskId={}, clientId={}, round={}",
                gradient.getTaskId(), gradient.getClientId(), gradient.getRound());

        checkRoundCompletion(task);
        return true;
    }

    private void checkRoundCompletion(TrainingTask task) {
        int received = task.getReceivedGradients().size();
        if (received >= task.getMinParticipants()) {
            cancelRoundTimeout(task.getTaskId());
            performAggregation(task);
        }
    }

    private void performAggregation(TrainingTask task) {
        task.setStatus(TrainingTask.Status.AGGREGATING);
        log.info("Starting aggregation: taskId={}, round={}, gradients={}",
                task.getTaskId(), task.getCurrentRound(), task.getReceivedGradients().size());

        try {
            GlobalModel currentModel = task.getFinalModel() != null ? task.getFinalModel() : task.getInitialModel();
            GlobalModel newModel = aggregator.aggregate(currentModel, task.getReceivedGradients().values());

            task.setFinalModel(newModel);
            models.put(task.getModelName(), newModel);

            if (task.getCurrentRound() >= task.getTotalRounds()) {
                task.setStatus(TrainingTask.Status.COMPLETED);
                task.setCompletedAt(Instant.now());
                log.info("Training completed: taskId={}, model={}, version={}",
                        task.getTaskId(), task.getModelName(), newModel.getVersion());
            } else {
                task.setCurrentRound(task.getCurrentRound() + 1);
                task.setReceivedGradients(new ConcurrentHashMap<>());
                task.setStatus(TrainingTask.Status.DISPATCHED);
                scheduleRoundTimeout(task.getTaskId(), task.getRoundTimeout());
                log.info("Round completed: taskId={}, newRound={}",
                        task.getTaskId(), task.getCurrentRound());
            }
        } catch (Exception e) {
            task.setStatus(TrainingTask.Status.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(Instant.now());
            log.error("Aggregation failed: taskId={}", task.getTaskId(), e);
        }
    }

    private GlobalModel createInitialModel(String modelName) {
        Map<String, double[]> weights = new HashMap<>();
        weights.put("dense_1", new double[100]);
        weights.put("dense_2", new double[50]);

        return GlobalModel.builder()
                .modelId(UUID.randomUUID().toString())
                .name(modelName)
                .version(1)
                .weights(weights)
                .round(0)
                .participantCount(0)
                .createdAt(Instant.now())
                .build();
    }

    private void scheduleRoundTimeout(String taskId, Duration timeout) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            TrainingTask task = tasks.get(taskId);
            if (task != null && task.getStatus() == TrainingTask.Status.DISPATCHED) {
                log.warn("Round timeout: taskId={}", taskId);
                if (task.getReceivedGradients().size() > 0) {
                    performAggregation(task);
                } else {
                    task.setStatus(TrainingTask.Status.FAILED);
                    task.setErrorMessage("Round timeout with no gradients received");
                    task.setCompletedAt(Instant.now());
                }
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        roundTimeouts.put(taskId, future);
    }

    private void cancelRoundTimeout(String taskId) {
        ScheduledFuture<?> future = roundTimeouts.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public Optional<TrainingTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<GlobalModel> getModel(String modelName) {
        return Optional.ofNullable(models.get(modelName));
    }

    public List<TrainingTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public void shutdown() {
        for (ScheduledFuture<?> future : roundTimeouts.values()) {
            future.cancel(false);
        }
        scheduler.shutdown();
        log.info("FederatedCoordinator shutdown");
    }

}
