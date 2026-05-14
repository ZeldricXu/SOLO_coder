package com.contractmgmt.service;

import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ReminderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class AsyncReminderService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncReminderService.class);

    private final ReminderConfigRepository reminderConfigRepository;
    private final ContractRepository contractRepository;
    private final ContractConfig contractConfig;
    private final ConcurrentLinkedQueue<ReminderTask> taskQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean processing = false;

    public AsyncReminderService(
            ReminderConfigRepository reminderConfigRepository,
            ContractRepository contractRepository,
            ContractConfig contractConfig) {
        this.reminderConfigRepository = reminderConfigRepository;
        this.contractRepository = contractRepository;
        this.contractConfig = contractConfig;
    }

    @Async
    public CompletableFuture<ReminderConfig> createExpireReminderAsync(String contractId, LocalDate contractEnd) {
        logger.info("异步处理合同到期提醒配置: {}", contractId);

        ReminderTask task = new ReminderTask(contractId, contractEnd);
        taskQueue.offer(task);

        return CompletableFuture.supplyAsync(() -> processTask(task));
    }

    private ReminderConfig processTask(ReminderTask task) {
        try {
            Optional<ReminderConfig> existing = reminderConfigRepository
                    .findByContractIdAndReminderType(task.contractId, "expire");

            if (existing.isPresent()) {
                logger.debug("合同 {} 提醒配置已存在，跳过创建", task.contractId);
                return existing.get();
            }

            ContractConfig.Reminder reminderConfig = contractConfig.getReminder();
            Integer advanceDays = reminderConfig != null ? reminderConfig.getAdvanceDays() : 15;
            if (advanceDays == null || advanceDays < 0) {
                advanceDays = 15;
            }

            LocalDate reminderTime = task.contractEnd.minusDays(advanceDays);

            ReminderConfig reminder = new ReminderConfig();
            reminder.setReminderId("reminder_async_" + System.currentTimeMillis());
            reminder.setContractId(task.contractId);
            reminder.setReminderType("expire");
            reminder.setReminderTime(reminderTime);
            reminder.setReminderChannel("email");
            reminder.setReminderStatus("pending");
            reminder.setRetryCount(0);
            reminderConfigRepository.save(reminder);

            logger.info("异步创建合同到期提醒配置完成: {}, 提醒时间: {}", task.contractId, reminderTime);
            return reminder;

        } catch (Exception e) {
            logger.error("异步创建提醒配置失败: {}", task.contractId, e);
            return null;
        }
    }

    @Async
    public CompletableFuture<Void> createReminderBatchAsync(String contractId, LocalDate contractEnd, String[] channels) {
        logger.info("异步批量创建提醒配置: {}, 渠道: {}", contractId, channels);

        return CompletableFuture.runAsync(() -> {
            for (String channel : channels) {
                try {
                    Optional<ReminderConfig> existing = reminderConfigRepository
                            .findByContractIdAndReminderType(contractId, "expire_" + channel);

                    if (existing.isPresent()) {
                        continue;
                    }

                    ContractConfig.Reminder reminderConfig = contractConfig.getReminder();
                    Integer advanceDays = reminderConfig != null ? reminderConfig.getAdvanceDays() : 15;
                    if (advanceDays == null) advanceDays = 15;

                    LocalDate reminderTime = contractEnd.minusDays(advanceDays);

                    ReminderConfig reminder = new ReminderConfig();
                    reminder.setReminderId("reminder_multi_" + System.currentTimeMillis() + "_" + channel);
                    reminder.setContractId(contractId);
                    reminder.setReminderType("expire_" + channel);
                    reminder.setReminderTime(reminderTime);
                    reminder.setReminderChannel(channel);
                    reminder.setReminderStatus("pending");
                    reminder.setRetryCount(0);
                    reminderConfigRepository.save(reminder);

                    logger.debug("异步创建多渠道提醒: 合同={}, 渠道={}", contractId, channel);

                } catch (Exception e) {
                    logger.error("异步创建多渠道提醒失败: 合同={}, 渠道={}", contractId, channel, e);
                }
            }
        });
    }

    public int getPendingTaskCount() {
        return taskQueue.size();
    }

    public void clearCompletedTasks() {
        taskQueue.removeIf(task -> task.completed);
    }

    public static class ReminderTask {
        final String contractId;
        final LocalDate contractEnd;
        volatile boolean completed = false;
        volatile ReminderConfig result = null;

        public ReminderTask(String contractId, LocalDate contractEnd) {
            this.contractId = contractId;
            this.contractEnd = contractEnd;
        }
    }
}
