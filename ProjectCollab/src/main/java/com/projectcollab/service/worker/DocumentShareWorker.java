package com.projectcollab.service.worker;

import com.projectcollab.config.properties.DocumentShareProperties;
import com.projectcollab.dto.DocumentShareTask;
import com.projectcollab.entity.Document;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.service.member.MemberService;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.service.queue.DocumentShareQueueService;
import com.projectcollab.service.reminder.ReminderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DocumentShareWorker {

    private static final Logger logger = LoggerFactory.getLogger(DocumentShareWorker.class);

    @Autowired
    private DocumentShareQueueService queueService;

    @Autowired
    private DocumentShareProperties properties;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private ProjectService projectService;

    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    @PostConstruct
    public void start() {
        int poolSize = properties.getWorkerPoolSize();
        workerPool = Executors.newFixedThreadPool(poolSize);
        running.set(true);

        for (int i = 0; i < poolSize; i++) {
            workerPool.submit(new WorkerRunnable("share-worker-" + (i + 1)));
        }

        logger.info("文档共享Worker已启动，Worker数量: {}", poolSize);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
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
        logger.info("文档共享Worker已停止");
    }

    private class WorkerRunnable implements Runnable {
        private final String workerName;

        public WorkerRunnable(String workerName) {
            this.workerName = workerName;
        }

        @Override
        public void run() {
            logger.debug("Worker {} 已就绪", workerName);
            
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    DocumentShareTask task = queueService.dequeueTask(5, TimeUnit.SECONDS);
                    
                    if (task != null) {
                        processTask(task);
                    }
                } catch (Exception e) {
                    logger.error("Worker {} 处理任务时出错: {}", workerName, e.getMessage(), e);
                }
            }
            
            logger.debug("Worker {} 已退出", workerName);
        }
    }

    private void processTask(DocumentShareTask task) {
        logger.debug("Worker开始处理文档共享任务: taskId={}, docId={}", task.getTaskId(), task.getDocumentId());
        
        try {
            Project project = projectService.getProjectById(task.getProjectId())
                    .orElse(null);
            
            if (project == null) {
                logger.warn("项目不存在，跳过文档共享: projectId={}", task.getProjectId());
                return;
            }

            List<ProjectMember> members = memberService.getMembersByProjectId(task.getProjectId());
            int notifiedCount = 0;

            for (ProjectMember member : members) {
                if (!member.getUserId().equals(task.getUploaderId())) {
                    try {
                        reminderService.createDocumentShareNotification(
                                project,
                                task.getDocumentName(),
                                member.getUserId()
                        );
                        notifiedCount++;
                    } catch (Exception e) {
                        logger.warn("发送文档共享通知失败: userId={}, docId={}", 
                                member.getUserId(), task.getDocumentId());
                    }
                }
            }

            processedCount.incrementAndGet();
            task.setStatus("COMPLETED");
            
            logger.info("文档共享任务完成: taskId={}, docId={}, notifiedMembers={}", 
                    task.getTaskId(), task.getDocumentId(), notifiedCount);
            
        } catch (Exception e) {
            failedCount.incrementAndGet();
            task.setStatus("FAILED");
            logger.error("文档共享任务处理失败: taskId={}, docId={}", 
                    task.getTaskId(), task.getDocumentId(), e);
            
            if (task.getRetryCount() < properties.getMaxRetries()) {
                try {
                    Thread.sleep(properties.getRetryDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                queueService.requeueTask(task);
            }
        }
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public void resetCounters() {
        processedCount.set(0);
        failedCount.set(0);
    }

    public boolean isRunning() {
        return running.get();
    }
}
