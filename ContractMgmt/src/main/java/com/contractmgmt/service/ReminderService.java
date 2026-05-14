package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ReminderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderConfigRepository reminderConfigRepository;
    private final ContractRepository contractRepository;
    private final ContractConfig contractConfig;

    public ReminderService(
            ReminderConfigRepository reminderConfigRepository,
            ContractRepository contractRepository,
            ContractConfig contractConfig) {
        this.reminderConfigRepository = reminderConfigRepository;
        this.contractRepository = contractRepository;
        this.contractConfig = contractConfig;
    }

    @Transactional
    public ReminderConfig createExpireReminder(String contractId, LocalDate contractEnd) {
        Optional<ReminderConfig> existing = reminderConfigRepository
                .findByContractIdAndReminderType(contractId, "expire");

        if (existing.isPresent()) {
            return existing.get();
        }

        Integer advanceDays = contractConfig.getReminder().getAdvanceDays();
        if (advanceDays == null) {
            advanceDays = 15;
        }

        LocalDate reminderTime = contractEnd.minusDays(advanceDays);

        ReminderConfig reminder = new ReminderConfig();
        reminder.setReminderId("reminder_" + IdUtil.getSnowflakeNextIdStr());
        reminder.setContractId(contractId);
        reminder.setReminderType("expire");
        reminder.setReminderTime(reminderTime);
        reminder.setReminderChannel("email");
        reminder.setReminderStatus("pending");
        reminder.setRetryCount(0);

        logger.info("创建到期提醒: 合同={}, 提醒时间={}", contractId, reminderTime);
        return reminderConfigRepository.save(reminder);
    }

    @Transactional
    public void checkAndSendReminders() {
        LocalDate today = LocalDate.now();
        List<ReminderConfig> pendingReminders = reminderConfigRepository
                .findByReminderTimeAndStatusIn(today, List.of("pending", "failed"));

        for (ReminderConfig reminder : pendingReminders) {
            sendReminder(reminder);
        }
    }

    private void sendReminder(ReminderConfig reminder) {
        try {
            Optional<Contract> contractOpt = contractRepository.findByContractId(reminder.getContractId());
            if (contractOpt.isEmpty()) {
                logger.warn("合同不存在，跳过提醒: {}", reminder.getContractId());
                reminder.setReminderStatus("failed");
                reminderConfigRepository.save(reminder);
                return;
            }

            Contract contract = contractOpt.get();
            String message = buildReminderMessage(contract, reminder);

            sendNotification(reminder.getReminderChannel(), message);

            reminder.setReminderStatus("sent");
            reminder.setSentTime(LocalDateTime.now());
            logger.info("发送到期提醒成功: 合同={}, 渠道={}", reminder.getContractId(), reminder.getReminderChannel());

        } catch (Exception e) {
            logger.error("发送到期提醒失败: {}", reminder.getReminderId(), e);
            reminder.setReminderStatus("failed");
            reminder.setRetryCount(reminder.getRetryCount() + 1);
        }
        reminderConfigRepository.save(reminder);
    }

    private String buildReminderMessage(Contract contract, ReminderConfig reminder) {
        return String.format(
                "[合同到期提醒] 合同 %s(%s) 将于 %s 到期，请及时处理。合同金额: %.2f",
                contract.getContractName(),
                contract.getContractId(),
                contract.getContractEnd(),
                contract.getContractAmount()
        );
    }

    private void sendNotification(String channel, String message) {
        logger.info("通过 {} 发送提醒: {}", channel, message);
    }

    public List<ReminderConfig> getRemindersByContract(String contractId) {
        return reminderConfigRepository.findByContractId(contractId);
    }

    public List<ReminderConfig> getPendingReminders() {
        return reminderConfigRepository.findByReminderStatus("pending");
    }
}
