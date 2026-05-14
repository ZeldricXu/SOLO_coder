package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.entity.Reminder;
import com.crm.repository.ReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReminderService {

    @Autowired
    private ReminderRepository reminderRepository;

    @Transactional
    public Reminder createReminder(String customerId, String salesId, String reminderType, LocalDateTime reminderTime, String reminderContent) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setCustomerId(customerId);
        reminder.setSalesId(salesId);
        reminder.setReminderType(reminderType);
        reminder.setReminderTime(reminderTime);
        reminder.setReminderStatus("pending");
        reminder.setReminderContent(reminderContent);
        return reminderRepository.save(reminder);
    }

    @Transactional
    public void createFollowReminder(String customerId, String salesId, LocalDateTime nextFollowTime) {
        createReminder(customerId, salesId, "follow_remind", nextFollowTime, 
                "请跟进客户：" + customerId);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void processPendingReminders() {
        List<Reminder> pendingReminders = reminderRepository.findByReminderStatusAndReminderTimeBefore(
                "pending", LocalDateTime.now());
        
        for (Reminder reminder : pendingReminders) {
            sendReminder(reminder);
            reminder.setReminderStatus("sent");
            reminder.setSentTime(LocalDateTime.now());
            reminderRepository.save(reminder);
        }
    }

    private void sendReminder(Reminder reminder) {
        System.out.println("发送提醒: " + reminder.getReminderContent() + 
                " 给销售人员: " + reminder.getSalesId() + 
                " 客户: " + reminder.getCustomerId());
    }

    public List<Reminder> getCustomerReminders(String customerId) {
        return reminderRepository.findByCustomerId(customerId);
    }

    public List<Reminder> getSalesReminders(String salesId) {
        return reminderRepository.findBySalesId(salesId);
    }

    public List<Reminder> getPendingReminders() {
        return reminderRepository.findByReminderStatus("pending");
    }
}
