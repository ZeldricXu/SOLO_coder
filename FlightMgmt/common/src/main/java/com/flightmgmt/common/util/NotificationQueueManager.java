package com.flightmgmt.common.util;

import com.flightmgmt.common.model.NotificationTask;

import java.util.*;
import java.util.concurrent.*;

public class NotificationQueueManager {
    private static NotificationQueueManager instance;
    private final BlockingQueue<NotificationTask> pendingQueue;
    private final Map<String, NotificationTask> inProgressTasks;
    private final Map<String, NotificationTask> confirmedTasks;
    private final Map<String, NotificationTask> failedTasks;
    private final ExecutorService workerExecutor;
    private final List<NotificationWorker> workers;
    private volatile boolean running;
    private boolean useRedis;

    private NotificationQueueManager() {
        this.pendingQueue = new LinkedBlockingQueue<>();
        this.inProgressTasks = new ConcurrentHashMap<>();
        this.confirmedTasks = new ConcurrentHashMap<>();
        this.failedTasks = new ConcurrentHashMap<>();
        this.workerExecutor = Executors.newFixedThreadPool(3);
        this.workers = new ArrayList<>();
        this.running = false;
        this.useRedis = false;
    }

    public static synchronized NotificationQueueManager getInstance() {
        if (instance == null) {
            instance = new NotificationQueueManager();
        }
        return instance;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        
        for (int i = 0; i < 3; i++) {
            NotificationWorker worker = new NotificationWorker("Worker-" + (i + 1), this);
            workers.add(worker);
            workerExecutor.submit(worker);
        }
        
        System.out.println("NotificationQueueManager 已启动，工作线程: " + workers.size());
    }

    public void stop() {
        running = false;
        for (NotificationWorker worker : workers) {
            worker.stop();
        }
        workerExecutor.shutdown();
        System.out.println("NotificationQueueManager 已停止");
    }

    public boolean submitTask(NotificationTask task) {
        if (task == null || task.getTaskId() == null) {
            return false;
        }
        
        boolean added = pendingQueue.offer(task);
        if (added) {
            System.out.println("通知任务已入队: " + task.getTaskId() + " - " + task.getTitle());
        }
        return added;
    }

    public NotificationTask takeTask() throws InterruptedException {
        return pendingQueue.take();
    }

    public NotificationTask pollTask(long timeout, TimeUnit unit) throws InterruptedException {
        return pendingQueue.poll(timeout, unit);
    }

    public void markInProgress(NotificationTask task) {
        inProgressTasks.put(task.getTaskId(), task);
    }

    public void markConfirmed(String taskId) {
        NotificationTask task = inProgressTasks.remove(taskId);
        if (task != null) {
            task.setStatus("confirmed");
            task.setConfirmedAt(java.time.LocalDateTime.now());
            confirmedTasks.put(taskId, task);
            System.out.println("通知任务已确认: " + taskId);
        }
    }

    public void markFailed(String taskId, boolean canRetry) {
        NotificationTask task = inProgressTasks.remove(taskId);
        if (task != null) {
            if (canRetry && !task.isMaxRetriesReached()) {
                task.incrementRetry();
                task.setStatus("pending_retry");
                pendingQueue.offer(task);
                System.out.println("通知任务将重试: " + taskId + " (第" + task.getRetryCount() + "次)");
            } else {
                task.setStatus("failed");
                failedTasks.put(taskId, task);
                System.out.println("通知任务已失败: " + taskId);
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPendingCount() {
        return pendingQueue.size();
    }

    public int getInProgressCount() {
        return inProgressTasks.size();
    }

    public int getConfirmedCount() {
        return confirmedTasks.size();
    }

    public int getFailedCount() {
        return failedTasks.size();
    }

    public void setUseRedis(boolean useRedis) {
        this.useRedis = useRedis;
    }

    public boolean isUseRedis() {
        return useRedis;
    }

    public Collection<NotificationTask> getAllPendingTasks() {
        return new ArrayList<>(pendingQueue);
    }

    public Collection<NotificationTask> getAllConfirmedTasks() {
        return confirmedTasks.values();
    }

    public Collection<NotificationTask> getAllFailedTasks() {
        return failedTasks.values();
    }

    public void clearAll() {
        pendingQueue.clear();
        inProgressTasks.clear();
        confirmedTasks.clear();
        failedTasks.clear();
    }

    public static class NotificationWorker implements Runnable {
        private final String workerId;
        private final NotificationQueueManager queueManager;
        private volatile boolean running;

        public NotificationWorker(String workerId, NotificationQueueManager queueManager) {
            this.workerId = workerId;
            this.queueManager = queueManager;
            this.running = true;
        }

        @Override
        public void run() {
            System.out.println(workerId + " 已启动");
            while (running && queueManager.isRunning()) {
                try {
                    NotificationTask task = queueManager.pollTask(5, TimeUnit.SECONDS);
                    if (task != null) {
                        processTask(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println(workerId + " 处理任务时出错: " + e.getMessage());
                }
            }
            System.out.println(workerId + " 已停止");
        }

        private void processTask(NotificationTask task) {
            System.out.println(workerId + " 开始处理任务: " + task.getTaskId());
            queueManager.markInProgress(task);

            boolean sentSuccessfully = sendNotification(task);
            
            if (sentSuccessfully) {
                System.out.println(workerId + " 通知发送成功，等待确认: " + task.getTaskId());
                
                boolean confirmed = waitForConfirmation(task, 30, TimeUnit.SECONDS);
                
                if (confirmed) {
                    queueManager.markConfirmed(task.getTaskId());
                } else {
                    System.out.println(workerId + " 通知未确认，准备重试: " + task.getTaskId());
                    queueManager.markFailed(task.getTaskId(), true);
                }
            } else {
                System.out.println(workerId + " 通知发送失败: " + task.getTaskId());
                queueManager.markFailed(task.getTaskId(), true);
            }
        }

        private boolean sendNotification(NotificationTask task) {
            try {
                System.out.println("[" + workerId + "] 发送通知: " + task.getTitle() + 
                    " 给乘客 " + task.getPassengerId());
                Thread.sleep(10);
                return true;
            } catch (Exception e) {
                System.err.println("发送通知失败: " + e.getMessage());
                return false;
            }
        }

        private boolean waitForConfirmation(NotificationTask task, long timeout, TimeUnit unit) {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            
            while (System.currentTimeMillis() < deadline) {
                if (isTaskConfirmed(task.getTaskId())) {
                    return true;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private boolean isTaskConfirmed(String taskId) {
            return false;
        }

        public void stop() {
            this.running = false;
        }
    }
}
