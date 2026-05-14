package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.config.ReminderIntervalConfig;
import com.schedulebook.model.Booking;
import com.schedulebook.model.Reminder;
import com.schedulebook.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReminderService {
    
    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);
    
    @Autowired
    private ReminderRepository reminderRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Autowired
    private ReminderIntervalConfig reminderIntervalConfig;
    
    @Transactional
    public List<Reminder> createMultipleReminders(Booking booking) {
        logger.info("创建多重预约提醒，预约ID: {}, 时长: {}分钟", booking.getBookingId(), booking.getBookingDuration());
        
        List<Reminder> reminders = new ArrayList<>();
        
        List<ReminderIntervalConfig.ReminderRule> rules = 
                reminderIntervalConfig.getRulesForDuration(booking.getBookingDuration());
        
        logger.debug("为时长 {} 分钟的预约选择了 {} 个提醒规则", 
                booking.getBookingDuration(), rules.size());
        
        for (ReminderIntervalConfig.ReminderRule rule : rules) {
            ReminderConfig config = new ReminderConfig(
                    rule.getType(),
                    booking.getBookingTime().minusMinutes(rule.getMinutesBefore()),
                    rule.getChannel()
            );
            Reminder reminder = createReminderInternal(booking, config);
            reminders.add(reminder);
        }
        
        logger.info("已创建 {} 个提醒，预约ID: {}", reminders.size(), booking.getBookingId());
        return reminders;
    }
    
    @Transactional
    public Reminder createReminder(Booking booking) {
        List<ReminderIntervalConfig.ReminderRule> rules = 
                reminderIntervalConfig.getRulesForDuration(booking.getBookingDuration());
        
        if (!rules.isEmpty()) {
            ReminderIntervalConfig.ReminderRule firstRule = rules.get(0);
            ReminderConfig config = new ReminderConfig(
                    firstRule.getType(),
                    booking.getBookingTime().minusMinutes(firstRule.getMinutesBefore()),
                    firstRule.getChannel()
            );
            return createReminderInternal(booking, config);
        }
        
        ReminderConfig config = new ReminderConfig(
                ApplicationConfig.REMINDER_TYPE_BEFORE_TIME,
                booking.getBookingTime().minusMinutes(30),
                "sms"
        );
        return createReminderInternal(booking, config);
    }
    
    private Reminder createReminderInternal(Booking booking, ReminderConfig config) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(idGeneratorService.generateReminderId());
        reminder.setBookingId(booking.getBookingId());
        reminder.setReminderType(config.type);
        reminder.setReminderTime(config.time);
        reminder.setReminderChannel(config.channel);
        reminder.setReminderStatus(ApplicationConfig.REMINDER_STATUS_PENDING);
        reminder.setCreatedAt(LocalDateTime.now());
        
        reminder = reminderRepository.save(reminder);
        logger.debug("提醒创建成功，类型: {}, 时间: {}, 渠道: {}", config.type, config.time, config.channel);
        return reminder;
    }
    
    public List<ReminderConfig> calculateReminderTimes(Booking booking) {
        List<ReminderConfig> configs = new ArrayList<>();
        
        List<ReminderIntervalConfig.ReminderRule> rules = 
                reminderIntervalConfig.getRulesForDuration(booking.getBookingDuration());
        
        for (ReminderIntervalConfig.ReminderRule rule : rules) {
            configs.add(new ReminderConfig(
                    rule.getType(),
                    booking.getBookingTime().minusMinutes(rule.getMinutesBefore()),
                    rule.getChannel()
            ));
        }
        
        return configs;
    }
    
    public int getReminderCountByDuration(int durationMinutes) {
        return reminderIntervalConfig.getRulesForDuration(durationMinutes).size();
    }
    
    public String getDurationCategory(int durationMinutes) {
        if (durationMinutes <= 30) {
            return "short";
        } else if (durationMinutes <= 120) {
            return "medium";
        } else if (durationMinutes <= 480) {
            return "long";
        } else {
            return "all_day";
        }
    }
    
    @Transactional
    public List<Reminder> handleMissedReminders(String bookingId) {
        logger.info("处理遗漏的提醒，预约ID: {}", bookingId);
        
        List<Reminder> pendingReminders = reminderRepository.findByBookingIdAndReminderStatus(
                bookingId, ApplicationConfig.REMINDER_STATUS_PENDING);
        
        List<Reminder> sentReminders = new ArrayList<>();
        
        for (Reminder reminder : pendingReminders) {
            if (isReminderTimePassed(reminder)) {
                reminder.setReminderStatus(ApplicationConfig.REMINDER_STATUS_SENT);
                reminder.setSentAt(LocalDateTime.now());
                reminderRepository.save(reminder);
                sentReminders.add(reminder);
                logger.info("补充发送遗漏的提醒，提醒ID: {}", reminder.getReminderId());
            }
        }
        
        logger.info("补充发送了 {} 个遗漏的提醒", sentReminders.size());
        return sentReminders;
    }
    
    private boolean isReminderTimePassed(Reminder reminder) {
        LocalTime now = LocalTime.now();
        return reminder.getReminderTime().isBefore(now) || reminder.getReminderTime().equals(now);
    }
    
    @Transactional
    public void cancelReminders(String bookingId) {
        logger.info("取消预约提醒，预约ID: {}", bookingId);
        
        List<Reminder> reminders = reminderRepository.findByBookingIdAndReminderStatus(
                bookingId, ApplicationConfig.REMINDER_STATUS_PENDING);
        
        for (Reminder reminder : reminders) {
            reminder.setReminderStatus(ApplicationConfig.REMINDER_STATUS_CANCELLED);
            reminderRepository.save(reminder);
        }
        
        logger.info("已取消 {} 个提醒，预约ID: {}", reminders.size(), bookingId);
    }
    
    @Transactional
    public void sendReminder(String reminderId) {
        logger.info("发送提醒，提醒ID: {}", reminderId);
        
        Reminder reminder = reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new RuntimeException("提醒不存在"));
        
        if (ApplicationConfig.REMINDER_STATUS_PENDING.equals(reminder.getReminderStatus())) {
            reminder.setReminderStatus(ApplicationConfig.REMINDER_STATUS_SENT);
            reminder.setSentAt(LocalDateTime.now());
            reminderRepository.save(reminder);
            logger.info("提醒发送成功，提醒ID: {}", reminderId);
        }
    }
    
    public Reminder getReminder(String reminderId) {
        return reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new RuntimeException("提醒不存在"));
    }
    
    public List<Reminder> getRemindersByBooking(String bookingId) {
        return reminderRepository.findByBookingId(bookingId);
    }
    
    public List<Reminder> getPendingReminders() {
        return reminderRepository.findByReminderStatus(ApplicationConfig.REMINDER_STATUS_PENDING);
    }
    
    public List<ReminderIntervalConfig.ReminderRule> getReminderRulesForCategory(String category) {
        return reminderIntervalConfig.getRules().getOrDefault(category, new ArrayList<>());
    }
    
    public void updateReminderRule(String category, String ruleType, int minutesBefore, String channel) {
        List<ReminderIntervalConfig.ReminderRule> rules = 
                reminderIntervalConfig.getRules().computeIfAbsent(category, k -> new ArrayList<>());
        
        for (ReminderIntervalConfig.ReminderRule rule : rules) {
            if (ruleType.equals(rule.getType())) {
                rule.setMinutesBefore(minutesBefore);
                rule.setChannel(channel);
                logger.info("更新提醒规则，分类: {}, 类型: {}, 提前分钟数: {}, 渠道: {}", 
                        category, ruleType, minutesBefore, channel);
                return;
            }
        }
        
        rules.add(new ReminderIntervalConfig.ReminderRule(ruleType, minutesBefore, channel));
        logger.info("添加新提醒规则，分类: {}, 类型: {}, 提前分钟数: {}, 渠道: {}", 
                category, ruleType, minutesBefore, channel);
    }
    
    public void removeReminderRule(String category, String ruleType) {
        List<ReminderIntervalConfig.ReminderRule> rules = 
                reminderIntervalConfig.getRules().get(category);
        
        if (rules != null) {
            rules.removeIf(rule -> ruleType.equals(rule.getType()));
            logger.info("移除提醒规则，分类: {}, 类型: {}", category, ruleType);
        }
    }
    
    public static class ReminderConfig {
        public final String type;
        public final LocalTime time;
        public final String channel;
        
        public ReminderConfig(String type, LocalTime time, String channel) {
            this.type = type;
            this.time = time;
            this.channel = channel;
        }
    }
}
