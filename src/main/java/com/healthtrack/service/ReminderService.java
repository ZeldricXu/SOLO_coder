package com.healthtrack.service;

import com.healthtrack.entity.HealthReminder;
import com.healthtrack.repository.HealthReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReminderService {

    @Autowired
    private HealthReminderRepository healthReminderRepository;

    @Autowired
    private HistoryService historyService;

    public HealthReminder createReminder(HealthReminder reminder) {
        reminder.setReminderId("reminder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        reminder.setEnabled(true);
        return healthReminderRepository.save(reminder);
    }

    public List<HealthReminder> getUserReminders(String userId) {
        return healthReminderRepository.findByUserId(userId);
    }

    public Optional<HealthReminder> getReminderById(String reminderId) {
        return healthReminderRepository.findById(reminderId);
    }

    public HealthReminder updateReminder(String reminderId, HealthReminder updatedReminder) {
        return healthReminderRepository.findById(reminderId)
                .map(reminder -> {
                    reminder.setReminderType(updatedReminder.getReminderType());
                    reminder.setReminderTime(updatedReminder.getReminderTime());
                    reminder.setReminderContent(updatedReminder.getReminderContent());
                    reminder.setFrequency(updatedReminder.getFrequency());
                    reminder.setEnabled(updatedReminder.getEnabled());
                    return healthReminderRepository.save(reminder);
                })
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在: " + reminderId));
    }

    public void deleteReminder(String reminderId) {
        healthReminderRepository.deleteById(reminderId);
    }

    public void checkAndTriggerAbnormalityReminder(String userId, String dataType, Double value) {
        String reminderType = getAbnormalityReminderType(dataType);
        String reminderContent = generateAbnormalityReminderContent(dataType, value);
        
        historyService.recordHistory(userId, dataType, "REMINDER_TRIGGERED", 
                null, value, "异常提醒: " + reminderContent);
        
        System.out.println("触发异常提醒 - 用户: " + userId + ", 类型: " + reminderType + ", 内容: " + reminderContent);
    }

    private String getAbnormalityReminderType(String dataType) {
        switch (dataType.toLowerCase()) {
            case "heart_rate":
            case "blood_pressure_systolic":
            case "blood_pressure_diastolic":
                return "vital_signs";
            case "temperature":
                return "fever";
            default:
                return "health";
        }
    }

    private String generateAbnormalityReminderContent(String dataType, Double value) {
        return "您的" + dataType + "指标异常，当前值为: " + value + "，请关注您的健康状况。";
    }

    public void checkScheduledReminders() {
        List<HealthReminder> activeReminders = healthReminderRepository.findByEnabledTrue();
        LocalDateTime now = LocalDateTime.now();
        String currentTime = String.format("%02d:%02d", now.getHour(), now.getMinute());
        
        for (HealthReminder reminder : activeReminders) {
            if (shouldTriggerReminder(reminder, currentTime, now)) {
                triggerReminder(reminder);
            }
        }
    }

    private boolean shouldTriggerReminder(HealthReminder reminder, String currentTime, LocalDateTime now) {
        if (!reminder.getReminderTime().equals(currentTime)) {
            return false;
        }
        
        LocalDateTime lastTriggered = reminder.getLastTriggeredAt();
        if (lastTriggered == null) {
            return true;
        }
        
        switch (reminder.getFrequency().toLowerCase()) {
            case "daily":
                return lastTriggered.toLocalDate().isBefore(now.toLocalDate());
            case "weekly":
                return lastTriggered.plusDays(7).isBefore(now);
            case "monthly":
                return lastTriggered.plusMonths(1).isBefore(now);
            default:
                return false;
        }
    }

    private void triggerReminder(HealthReminder reminder) {
        reminder.setLastTriggeredAt(LocalDateTime.now());
        healthReminderRepository.save(reminder);
        
        historyService.recordHistory(reminder.getUserId(), reminder.getReminderType(), 
                "REMINDER_SENT", null, null, "发送提醒: " + reminder.getReminderContent());
        
        System.out.println("发送定时提醒 - 用户: " + reminder.getUserId() + ", 内容: " + reminder.getReminderContent());
    }
}
