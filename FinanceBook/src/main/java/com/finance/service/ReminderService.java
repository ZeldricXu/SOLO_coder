package com.finance.service;

import com.finance.entity.Reminder;
import com.finance.repository.ReminderRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;

    @Transactional
    public Reminder sendBudgetReminder(String accountId, String category, BigDecimal budgetAmount, BigDecimal usedAmount) {
        String content = String.format("预算超限提醒: 账户[%s]的[%s]分类预算已超限。预算: %s, 已使用: %s",
                accountId, category, budgetAmount, usedAmount);

        Reminder reminder = Reminder.builder()
                .reminderId(IdGenerator.generateReminderId())
                .accountId(accountId)
                .reminderType("budget_limit")
                .reminderContent(content)
                .reminderTime(LocalDateTime.now())
                .reminderStatus("sent")
                .createdAt(LocalDateTime.now())
                .build();

        Reminder saved = reminderRepository.save(reminder);
        log.warn("发送预算超限提醒: reminderId={}, accountId={}, category={}", saved.getReminderId(), accountId, category);

        System.out.println("[FINANCE REMINDER] " + content);
        return saved;
    }

    @Transactional
    public Reminder sendCustomReminder(String accountId, String reminderType, String content) {
        Reminder reminder = Reminder.builder()
                .reminderId(IdGenerator.generateReminderId())
                .accountId(accountId)
                .reminderType(reminderType)
                .reminderContent(content)
                .reminderTime(LocalDateTime.now())
                .reminderStatus("sent")
                .createdAt(LocalDateTime.now())
                .build();

        Reminder saved = reminderRepository.save(reminder);
        log.info("发送自定义提醒: reminderId={}, type={}", saved.getReminderId(), reminderType);

        System.out.println("[FINANCE REMINDER] " + content);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Reminder> getRemindersByAccount(String accountId) {
        return reminderRepository.findByAccountIdOrderByReminderTimeDesc(accountId);
    }

    @Transactional(readOnly = true)
    public List<Reminder> getRemindersByType(String reminderType) {
        return reminderRepository.findByReminderType(reminderType);
    }

    @Transactional(readOnly = true)
    public List<Reminder> getPendingReminders() {
        return reminderRepository.findByReminderStatus("pending");
    }

    @Transactional
    public Reminder updateReminderStatus(String reminderId, String status) {
        Reminder reminder = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new RuntimeException("提醒不存在: " + reminderId));
        reminder.setReminderStatus(status);
        return reminderRepository.save(reminder);
    }
}
