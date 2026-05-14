package com.deviceops.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "deviceops")
public class DeviceOpsProperties {

    private Config config = new Config();
    private Queue queue = new Queue();
    private Worker worker = new Worker();
    private Alert alert = new Alert();
    private Task task = new Task();

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public Queue getQueue() {
        return queue;
    }

    public void setQueue(Queue queue) {
        this.queue = queue;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public static class Config {
        private long refreshIntervalMs = 30000;

        public long getRefreshIntervalMs() {
            return refreshIntervalMs;
        }

        public void setRefreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
        }
    }

    public static class Queue {
        private String redisQueueKey = "deviceops:fault:queue";
        private String processingSetKey = "deviceops:fault:processing";
        private String deadLetterKey = "deviceops:fault:deadletter";

        public String getRedisQueueKey() {
            return redisQueueKey;
        }

        public void setRedisQueueKey(String redisQueueKey) {
            this.redisQueueKey = redisQueueKey;
        }

        public String getProcessingSetKey() {
            return processingSetKey;
        }

        public void setProcessingSetKey(String processingSetKey) {
            this.processingSetKey = processingSetKey;
        }

        public String getDeadLetterKey() {
            return deadLetterKey;
        }

        public void setDeadLetterKey(String deadLetterKey) {
            this.deadLetterKey = deadLetterKey;
        }
    }

    public static class Worker {
        private int threadCount = 4;
        private long pollIntervalMs = 100;

        public int getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(int threadCount) {
            this.threadCount = threadCount;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }
    }

    public static class Alert {
        private RetryConfig retry = new RetryConfig();

        public RetryConfig getRetry() {
            return retry;
        }

        public void setRetry(RetryConfig retry) {
            this.retry = retry;
        }
    }

    public static class RetryConfig {
        private LevelConfig high = new LevelConfig(5, 30, true);
        private LevelConfig medium = new LevelConfig(3, 60, true);
        private LevelConfig low = new LevelConfig(1, 300, false);

        public LevelConfig getHigh() {
            return high;
        }

        public void setHigh(LevelConfig high) {
            this.high = high;
        }

        public LevelConfig getMedium() {
            return medium;
        }

        public void setMedium(LevelConfig medium) {
            this.medium = medium;
        }

        public LevelConfig getLow() {
            return low;
        }

        public void setLow(LevelConfig low) {
            this.low = low;
        }

        public LevelConfig getByLevel(String level) {
            return switch (level) {
                case "high" -> high;
                case "medium" -> medium;
                default -> low;
            };
        }
    }

    public static class LevelConfig {
        private int maxRetries;
        private int intervalSeconds;
        private boolean autoRetry;

        public LevelConfig() {
        }

        public LevelConfig(int maxRetries, int intervalSeconds, boolean autoRetry) {
            this.maxRetries = maxRetries;
            this.intervalSeconds = intervalSeconds;
            this.autoRetry = autoRetry;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public boolean isAutoRetry() {
            return autoRetry;
        }

        public void setAutoRetry(boolean autoRetry) {
            this.autoRetry = autoRetry;
        }
    }

    public static class Task {
        private LockConfig lock = new LockConfig();

        public LockConfig getLock() {
            return lock;
        }

        public void setLock(LockConfig lock) {
            this.lock = lock;
        }
    }

    public static class LockConfig {
        private LockLevelConfig high = new LockLevelConfig(1800, 60, true);
        private LockLevelConfig medium = new LockLevelConfig(3600, 300, true);
        private LockLevelConfig low = new LockLevelConfig(7200, 600, false);

        public LockLevelConfig getHigh() {
            return high;
        }

        public void setHigh(LockLevelConfig high) {
            this.high = high;
        }

        public LockLevelConfig getMedium() {
            return medium;
        }

        public void setMedium(LockLevelConfig medium) {
            this.medium = medium;
        }

        public LockLevelConfig getLow() {
            return low;
        }

        public void setLow(LockLevelConfig low) {
            this.low = low;
        }

        public LockLevelConfig getByPriority(String priority) {
            return switch (priority) {
                case "high" -> high;
                case "medium" -> medium;
                default -> low;
            };
        }
    }

    public static class LockLevelConfig {
        private int timeoutSeconds;
        private int maxWaitSeconds;
        private boolean priorityBoost;

        public LockLevelConfig() {
        }

        public LockLevelConfig(int timeoutSeconds, int maxWaitSeconds, boolean priorityBoost) {
            this.timeoutSeconds = timeoutSeconds;
            this.maxWaitSeconds = maxWaitSeconds;
            this.priorityBoost = priorityBoost;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxWaitSeconds() {
            return maxWaitSeconds;
        }

        public void setMaxWaitSeconds(int maxWaitSeconds) {
            this.maxWaitSeconds = maxWaitSeconds;
        }

        public boolean isPriorityBoost() {
            return priorityBoost;
        }

        public void setPriorityBoost(boolean priorityBoost) {
            this.priorityBoost = priorityBoost;
        }
    }
}
