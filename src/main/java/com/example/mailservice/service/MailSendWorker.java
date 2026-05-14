package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.dto.MailSendRequest;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.model.SendTask;
import com.example.mailservice.repository.MailRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailSendWorker {

    private final RedisQueueService redisQueueService;
    private final JavaMailSender mailSender;
    private final MailRecordRepository mailRecordRepository;
    private final StatusService statusService;
    private final ArchiveService archiveService;
    private final HistoryService historyService;
    private final AnalysisService analysisService;
    private final AppConfig appConfig;

    @Value("${spring.mail.username}")
    private String senderEmail;

    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @PostConstruct
    public void init() {
        redisQueueService.recoverProcessingTasks();
        int workerCount = appConfig.getRedisQueue().getSendWorkerCount();
        workerPool = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::workerLoop);
        }
        log.info("邮件发送Worker已启动，Worker数量: {}", workerCount);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdown();
        }
        log.info("邮件发送Worker已停止");
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                SendTask task = redisQueueService.pollSendTask();
                if (task != null) {
                    processSendTask(task);
                }
            } catch (Exception e) {
                log.error("发送Worker处理异常", e);
            }
        }
    }

    private void processSendTask(SendTask task) {
        log.info("开始处理发送任务，taskId: {}, mailId: {}", task.getTaskId(), task.getMailId());

        String mailId = task.getMailId();
        MailRecord mailRecord = mailRecordRepository.findByMailId(mailId).orElse(null);

        if (mailRecord == null) {
            log.warn("邮件记录不存在，mailId: {}", mailId);
            redisQueueService.markSendTaskCompleted(task.getTaskId());
            return;
        }

        boolean success = false;
        String smtpResponse = null;
        String errorMessage = null;

        try {
            MimeMessage message = buildMimeMessage(task);
            mailSender.send(message);
            smtpResponse = "250 OK";
            success = true;
            log.info("邮件发送成功，mailId: {}", mailId);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("邮件发送失败，mailId: {}, 错误: {}", mailId, errorMessage);
        }

        if (success) {
            mailRecord.setMailStatus("sent");
            mailRecord.setSentAt(LocalDateTime.now());
            mailRecordRepository.save(mailRecord);

            statusService.createStatus(mailId, "success", smtpResponse, null);
            archiveService.archiveMail(mailId, task.getCategory());
            historyService.recordHistory(mailId, "SENT", "邮件发送成功", "system");
            analysisService.incrementSentCount();

            redisQueueService.markSendTaskCompleted(task.getTaskId());
        } else {
            boolean shouldRetry = errorMessage != null && !errorMessage.contains("invalid") && !errorMessage.contains("rejected");
            task.setErrorMessage(errorMessage);

            if (shouldRetry && task.canRetry()) {
                mailRecord.setMailStatus("retrying");
                mailRecordRepository.save(mailRecord);
                statusService.createStatus(mailId, "retrying", null, "第" + (task.getRetryCount() + 1) + "次重试: " + errorMessage);
                redisQueueService.markSendTaskFailed(task, true);
            } else {
                mailRecord.setMailStatus("failed");
                mailRecordRepository.save(mailRecord);
                statusService.createStatus(mailId, "failed", null, errorMessage);
                historyService.recordHistory(mailId, "SEND_FAILED", "邮件发送失败: " + errorMessage, "system");
                analysisService.incrementFailedCount();
                redisQueueService.markSendTaskFailed(task, false);
            }
        }
    }

    private MimeMessage buildMimeMessage(SendTask task) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(task.getRecipients() != null ? task.getRecipients().toArray(new String[0]) : new String[0]);

        if (task.getCc() != null && !task.getCc().isEmpty()) {
            helper.setCc(task.getCc().toArray(new String[0]));
        }
        if (task.getBcc() != null && !task.getBcc().isEmpty()) {
            helper.setBcc(task.getBcc().toArray(new String[0]));
        }

        helper.setSubject(task.getSubject() != null ? task.getSubject() : "");
        helper.setText(task.getContent() != null ? task.getContent() : "",
                "text/html".equalsIgnoreCase(task.getContentType()));

        return message;
    }

    public void submitTask(MailSendRequest request, MailRecord record) {
        SendTask task = SendTask.builder()
                .taskId("task_" + record.getMailId())
                .mailId(record.getMailId())
                .recipients(request.getRecipients())
                .cc(request.getCc())
                .bcc(request.getBcc())
                .subject(request.getSubject())
                .content(request.getContent())
                .contentType(request.getContentType())
                .category(request.getCategory())
                .templateId(request.getTemplateId())
                .maxRetries(appConfig.getRedisQueue().getMaxRetries())
                .retryCount(0)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();
        redisQueueService.pushSendTask(task);
    }

    @Scheduled(fixedRate = 60000)
    public void monitorQueue() {
        long queueSize = redisQueueService.getSendQueueSize();
        long deadLetterSize = redisQueueService.getSendDeadLetterSize();
        if (queueSize > 0 || deadLetterSize > 0) {
            log.info("发送队列监控 - 待处理: {}, 死信: {}", queueSize, deadLetterSize);
        }
    }
}
