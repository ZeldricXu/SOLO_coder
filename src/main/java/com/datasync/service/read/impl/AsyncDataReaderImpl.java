package com.datasync.service.read.impl;

import com.datasync.model.DataReadTask;
import com.datasync.model.DataSourceConfig;
import com.datasync.service.config.ConfigManager;
import com.datasync.service.datasource.DataSourceAdapter;
import com.datasync.service.datasource.DataSourceAdapterFactory;
import com.datasync.service.read.AsyncDataReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AsyncDataReaderImpl implements AsyncDataReader {

    private static final Logger logger = LoggerFactory.getLogger(AsyncDataReaderImpl.class);

    public static final String REDIS_KEY_PREFIX_READ_TASK = "read_task:";
    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int DEFAULT_WORKER_COUNT = 4;
    public static final long DEFAULT_WAIT_TIMEOUT = 60000;

    private final Map<String, DataReadTask> taskCache = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<DataReadTask>>> callbacks = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> latches = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<DataReadTask> taskQueue =
            new PriorityBlockingQueue<>(100, Comparator.comparing(
                    (DataReadTask t) -> t.getPriority() != null ? t.getPriority() : 0,
                    Comparator.reverseOrder()
            ));

    private final AtomicInteger activeWorkerCount = new AtomicInteger(0);
    private final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private final AtomicInteger totalCompleted = new AtomicInteger(0);
    private final AtomicInteger totalFailed = new AtomicInteger(0);

    private ExecutorService workerPool;
    private volatile boolean running = false;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private DataSourceAdapterFactory adapterFactory;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        workerPool = Executors.newFixedThreadPool(DEFAULT_WORKER_COUNT, r -> {
            Thread t = new Thread(r);
            t.setName("data-reader-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        running = true;
        for (int i = 0; i < DEFAULT_WORKER_COUNT; i++) {
            workerPool.submit(this::workerLoop);
        }

        loadAllTasksFromPersistence();
        logger.info("AsyncDataReader initialized with {} worker threads", DEFAULT_WORKER_COUNT);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("AsyncDataReader shutdown completed");
    }

    @Override
    public void loadAllTasksFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_READ_TASK + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            DataReadTask task = objectMapper.readValue(json, DataReadTask.class);
                            taskCache.put(task.getReadTaskId(), task);

                            if (DataReadTask.STATUS_QUEUED.equals(task.getStatus()) ||
                                DataReadTask.STATUS_PENDING.equals(task.getStatus())) {
                                taskQueue.offer(task);
                                loaded++;
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load read task from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} pending read tasks from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load read tasks from Redis", e);
        }
    }

    private void workerLoop() {
        while (running) {
            try {
                DataReadTask task = taskQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }

                activeWorkerCount.incrementAndGet();
                try {
                    executeReadTask(task);
                } catch (Exception e) {
                    logger.error("Error executing read task: {}", task.getReadTaskId(), e);
                    task.markFailed(e.getMessage());
                    persistTask(task);
                    notifyCallbacks(task);
                } finally {
                    activeWorkerCount.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in worker loop", e);
            }
        }
    }

    private void executeReadTask(DataReadTask task) throws Exception {
        String readTaskId = task.getReadTaskId();
        logger.info("Starting read task: {} (table: {})", readTaskId, task.getTableName());

        task.markStarted();
        persistTask(task);

        Optional<DataSourceConfig> dsOpt = configManager.getDataSource(task.getDataSourceId());
        if (!dsOpt.isPresent()) {
            throw new Exception("Data source not found: " + task.getDataSourceId());
        }

        DataSourceConfig dsConfig = dsOpt.get();
        DataSourceAdapter adapter = adapterFactory.getAdapter(dsConfig);

        int batchSize = task.getBatchSize() != null ? task.getBatchSize() : DEFAULT_BATCH_SIZE;
        int offset = task.getOffset() != null ? task.getOffset() : 0;
        Integer limit = task.getLimit();

        List<Map<String, Object>> allData = new ArrayList<>();
        int totalRead = 0;

        while (running) {
            String filterCondition = buildFilterCondition(task.getFilterCondition(), offset, batchSize);

            logger.debug("Reading batch from table: {} offset: {} batchSize: {}",
                    task.getTableName(), offset, batchSize);

            List<Map<String, Object>> batch = adapter.readData(
                    task.getTableName(),
                    filterCondition,
                    task.getDataKeyField()
            );

            if (batch == null || batch.isEmpty()) {
                break;
            }

            allData.addAll(batch);
            totalRead += batch.size();

            task.setReadRecords(totalRead);
            persistTask(task);

            if (batch.size() < batchSize) {
                break;
            }

            if (limit != null && totalRead >= limit) {
                if (totalRead > limit) {
                    allData = allData.subList(0, limit);
                    totalRead = limit;
                }
                break;
            }

            offset += batchSize;
        }

        task.setReadData(allData);
        task.markCompleted(totalRead);
        persistTask(task);

        totalCompleted.incrementAndGet();

        logger.info("Read task completed: {} (total: {} records)", readTaskId, totalRead);

        notifyCallbacks(task);

        CountDownLatch latch = latches.remove(readTaskId);
        if (latch != null) {
            latch.countDown();
        }
    }

    private String buildFilterCondition(String baseCondition, int offset, int batchSize) {
        StringBuilder condition = new StringBuilder();

        if (baseCondition != null && !baseCondition.trim().isEmpty()) {
            condition.append(baseCondition);
        }

        if (condition.length() > 0) {
            condition.append(" LIMIT ").append(batchSize).append(" OFFSET ").append(offset);
        } else {
            condition.append("1=1 LIMIT ").append(batchSize).append(" OFFSET ").append(offset);
        }

        return condition.toString();
    }

    private void notifyCallbacks(DataReadTask task) {
        List<Consumer<DataReadTask>> taskCallbacks = callbacks.remove(task.getReadTaskId());
        if (taskCallbacks != null) {
            for (Consumer<DataReadTask> callback : taskCallbacks) {
                try {
                    callback.accept(task);
                } catch (Exception e) {
                    logger.error("Error executing callback for read task: {}", task.getReadTaskId(), e);
                }
            }
        }
    }

    private void persistTask(DataReadTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX_READ_TASK + task.getReadTaskId(), json);
        } catch (Exception e) {
            logger.warn("Failed to persist read task to Redis: {}", task.getReadTaskId(), e);
        }
    }

    @Override
    public DataReadTask submitReadTask(
            String taskId,
            String syncId,
            String dataSourceId,
            String tableName,
            String filterCondition,
            String dataKeyField) {
        return submitReadTask(taskId, syncId, dataSourceId, tableName, filterCondition,
                dataKeyField, DEFAULT_BATCH_SIZE, 0);
    }

    @Override
    public DataReadTask submitReadTask(
            String taskId,
            String syncId,
            String dataSourceId,
            String tableName,
            String filterCondition,
            String dataKeyField,
            Integer batchSize,
            Integer priority) {

        DataReadTask task = new DataReadTask();
        task.setTaskId(taskId);
        task.setSyncId(syncId);
        task.setDataSourceId(dataSourceId);
        task.setTableName(tableName);
        task.setFilterCondition(filterCondition);
        task.setDataKeyField(dataKeyField);
        task.setBatchSize(batchSize != null ? batchSize : DEFAULT_BATCH_SIZE);
        task.setPriority(priority != null ? priority : 0);

        return submitReadTask(task);
    }

    @Override
    public DataReadTask submitReadTask(DataReadTask task) {
        if (task.getReadTaskId() == null) {
            task.setReadTaskId("read_" + UUID.randomUUID().toString().substring(0, 12));
        }

        task.markQueued();
        taskCache.put(task.getReadTaskId(), task);
        persistTask(task);

        taskQueue.offer(task);
        totalSubmitted.incrementAndGet();

        CountDownLatch latch = new CountDownLatch(1);
        latches.put(task.getReadTaskId(), latch);

        logger.info("Submitted read task: {} (table: {}, priority: {})",
                task.getReadTaskId(), task.getTableName(), task.getPriority());

        return task;
    }

    @Override
    public Optional<DataReadTask> getReadTask(String readTaskId) {
        DataReadTask cached = taskCache.get(readTaskId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_READ_TASK + readTaskId);
            if (json != null) {
                DataReadTask task = objectMapper.readValue(json, DataReadTask.class);
                taskCache.put(readTaskId, task);
                return Optional.of(task);
            }
        } catch (Exception e) {
            logger.warn("Failed to get read task from Redis: {}", readTaskId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<DataReadTask> getReadTasksBySync(String syncId) {
        return taskCache.values().stream()
                .filter(t -> syncId.equals(t.getSyncId()))
                .sorted(Comparator.comparing(DataReadTask::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<DataReadTask> getReadTasksByTask(String taskId) {
        return taskCache.values().stream()
                .filter(t -> taskId.equals(t.getTaskId()))
                .sorted(Comparator.comparing(DataReadTask::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<DataReadTask> getPendingReadTasks() {
        return taskCache.values().stream()
                .filter(t -> DataReadTask.STATUS_PENDING.equals(t.getStatus()) ||
                             DataReadTask.STATUS_QUEUED.equals(t.getStatus()))
                .sorted(Comparator.comparing(DataReadTask::getCreatedAt))
                .collect(Collectors.toList());
    }

    @Override
    public List<DataReadTask> getRunningReadTasks() {
        return taskCache.values().stream()
                .filter(t -> DataReadTask.STATUS_RUNNING.equals(t.getStatus()) ||
                             DataReadTask.STATUS_PROCESSING.equals(t.getStatus()))
                .sorted(Comparator.comparing(DataReadTask::getStartedAt))
                .collect(Collectors.toList());
    }

    @Override
    public List<DataReadTask> getCompletedReadTasks() {
        return taskCache.values().stream()
                .filter(t -> DataReadTask.STATUS_COMPLETED.equals(t.getStatus()))
                .sorted(Comparator.comparing(DataReadTask::getCompletedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> waitForData(String readTaskId, long timeoutMs) throws InterruptedException {
        CountDownLatch latch = latches.get(readTaskId);
        if (latch == null) {
            Optional<DataReadTask> taskOpt = getReadTask(readTaskId);
            if (taskOpt.isPresent()) {
                DataReadTask task = taskOpt.get();
                if (task.isCompleted()) {
                    return task.getReadData();
                }
                if (task.isFailed()) {
                    throw new RuntimeException("Read task failed: " + task.getErrorMessage());
                }
            }
            return new ArrayList<>();
        }

        boolean completed = latch.await(timeoutMs > 0 ? timeoutMs : DEFAULT_WAIT_TIMEOUT, TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new TimeoutException("Read task timeout: " + readTaskId);
        }

        Optional<DataReadTask> taskOpt = getReadTask(readTaskId);
        if (taskOpt.isPresent()) {
            DataReadTask task = taskOpt.get();
            if (task.isFailed()) {
                throw new RuntimeException("Read task failed: " + task.getErrorMessage());
            }
            return task.getReadData() != null ? task.getReadData() : new ArrayList<>();
        }

        return new ArrayList<>();
    }

    @Override
    public void cancelReadTask(String readTaskId) {
        DataReadTask task = taskCache.get(readTaskId);
        if (task != null && !task.isTerminal()) {
            task.markCancelled();
            persistTask(task);
            logger.info("Cancelled read task: {}", readTaskId);

            CountDownLatch latch = latches.remove(readTaskId);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Override
    public void registerCallback(String readTaskId, Consumer<DataReadTask> callback) {
        callbacks.computeIfAbsent(readTaskId, k -> new ArrayList<>()).add(callback);

        Optional<DataReadTask> taskOpt = getReadTask(readTaskId);
        if (taskOpt.isPresent() && taskOpt.get().isTerminal()) {
            try {
                callback.accept(taskOpt.get());
            } catch (Exception e) {
                logger.error("Error executing callback for completed task: {}", readTaskId, e);
            }
        }
    }

    @Override
    public Map<String, Object> getReadTaskStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalSubmitted", totalSubmitted.get());
        stats.put("totalCompleted", totalCompleted.get());
        stats.put("totalFailed", totalFailed.get());
        stats.put("queueSize", taskQueue.size());
        stats.put("activeWorkers", activeWorkerCount.get());
        stats.put("totalWorkers", DEFAULT_WORKER_COUNT);

        int pending = (int) taskCache.values().stream()
                .filter(t -> !t.isTerminal()).count();
        int completed = (int) taskCache.values().stream()
                .filter(t -> DataReadTask.STATUS_COMPLETED.equals(t.getStatus())).count();
        int failed = (int) taskCache.values().stream()
                .filter(t -> DataReadTask.STATUS_FAILED.equals(t.getStatus())).count();

        stats.put("totalTasks", taskCache.size());
        stats.put("pendingTasks", pending);
        stats.put("completedTasks", completed);
        stats.put("failedTasks", failed);

        if (totalSubmitted.get() > 0) {
            double successRate = (double) totalCompleted.get() / totalSubmitted.get() * 100;
            stats.put("successRate", String.format("%.2f%%", successRate));
        }

        return stats;
    }

    @Override
    public int getQueueSize() {
        return taskQueue.size();
    }

    @Override
    public int getActiveWorkerCount() {
        return activeWorkerCount.get();
    }

    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
