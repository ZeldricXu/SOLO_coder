package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.IndexTask;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.repository.MailRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexWorker {

    private final RedisQueueService redisQueueService;
    private final MailRecordRepository mailRecordRepository;
    private final AppConfig appConfig;

    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @PostConstruct
    public void init() {
        int workerCount = appConfig.getRedisQueue().getIndexWorkerCount();
        workerPool = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::workerLoop);
        }
        log.info("索引Worker已启动，Worker数量: {}", workerCount);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdown();
        }
        log.info("索引Worker已停止");
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                IndexTask task = redisQueueService.pollIndexTask();
                if (task != null) {
                    processIndexTask(task);
                }
            } catch (Exception e) {
                log.error("索引Worker处理异常", e);
            }
        }
    }

    private void processIndexTask(IndexTask task) {
        log.info("开始处理索引任务，taskId: {}, mailId: {}", task.getTaskId(), task.getMailId());

        String mailId = task.getMailId();
        Optional<MailRecord> mailRecordOpt = mailRecordRepository.findByMailId(mailId);

        if (!mailRecordOpt.isPresent()) {
            log.warn("邮件记录不存在，跳过索引，mailId: {}", mailId);
            redisQueueService.markIndexTaskCompleted(task.getTaskId());
            return;
        }

        MailRecord mailRecord = mailRecordOpt.get();
        boolean success = false;
        String errorMessage = null;

        try {
            updateMailRecordIndex(mailRecord, task);
            success = true;
            log.info("索引建立成功，mailId: {}", mailId);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("索引建立失败，mailId: {}, 错误: {}", mailId, errorMessage);
        }

        if (success) {
            redisQueueService.markIndexTaskCompleted(task.getTaskId());
        } else {
            task.setErrorMessage(errorMessage);
            redisQueueService.markIndexTaskFailed(task, true);
        }
    }

    private void updateMailRecordIndex(MailRecord mailRecord, IndexTask task) {
        if (task.getSubject() != null && !task.getSubject().isEmpty()) {
            mailRecord.setSubject(task.getSubject());
        }
        if (task.getContent() != null && !task.getContent().isEmpty()) {
            mailRecord.setContent(task.getContent());
        }
        if (task.getCategory() != null && !task.getCategory().isEmpty()) {
            mailRecord.setCategory(task.getCategory());
        }
        if (task.getMailType() != null && !task.getMailType().isEmpty()) {
            mailRecord.setMailType(task.getMailType());
        }
        mailRecord.setUpdatedAt(LocalDateTime.now());
        mailRecordRepository.save(mailRecord);

        log.debug("邮件记录索引已更新，mailId: {}", mailRecord.getMailId());
    }

    public void submitTask(MailRecord record) {
        IndexTask task = IndexTask.builder()
                .taskId("idx_" + record.getMailId())
                .mailId(record.getMailId())
                .subject(record.getSubject())
                .content(record.getContent())
                .category(record.getCategory())
                .mailType(record.getMailType())
                .maxRetries(appConfig.getRedisQueue().getMaxRetries())
                .retryCount(0)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();
        redisQueueService.pushIndexTask(task);
    }

    @Scheduled(fixedRate = 60000)
    public void monitorQueue() {
        long queueSize = redisQueueService.getIndexQueueSize();
        long deadLetterSize = redisQueueService.getIndexDeadLetterSize();
        if (queueSize > 0 || deadLetterSize > 0) {
            log.info("索引队列监控 - 待处理: {}, 死信: {}", queueSize, deadLetterSize);
        }
    }
}
