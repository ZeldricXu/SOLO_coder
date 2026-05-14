package com.meeting.service;

import com.meeting.config.ReminderConfig;
import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.entity.Reminder;
import com.meeting.repository.AttendeeRepository;
import com.meeting.repository.MeetingRepository;
import com.meeting.repository.ReminderRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderConfirmService {

    private final ReminderRepository reminderRepository;
    private final AttendeeRepository attendeeRepository;
    private final MeetingRepository meetingRepository;
    private final ReminderConfig reminderConfig;

    private final Map<String, ReminderStatus> reminderStatusMap = new ConcurrentHashMap<>();

    public static final String IMPORTANCE_VIP = "vip";
    public static final String IMPORTANCE_IMPORTANT = "important";
    public static final String IMPORTANCE_NORMAL = "normal";

    public static class ReminderStatus {
        private final String reminderId;
        private final String meetingId;
        private final String userId;
        private final String importance;
        private int sentCount;
        private boolean confirmed;
        private LocalDateTime lastSentTime;
        private LocalDateTime confirmedTime;

        public ReminderStatus(String reminderId, String meetingId, String userId, String importance) {
            this.reminderId = reminderId;
            this.meetingId = meetingId;
            this.userId = userId;
            this.importance = importance;
            this.sentCount = 0;
            this.confirmed = false;
        }

        public String getReminderId() { return reminderId; }
        public String getMeetingId() { return meetingId; }
        public String getUserId() { return userId; }
        public String getImportance() { return importance; }
        public int getSentCount() { return sentCount; }
        public void incrementSentCount() { this.sentCount++; }
        public boolean isConfirmed() { return confirmed; }
        public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
        public LocalDateTime getLastSentTime() { return lastSentTime; }
        public void setLastSentTime(LocalDateTime lastSentTime) { this.lastSentTime = lastSentTime; }
        public LocalDateTime getConfirmedTime() { return confirmedTime; }
        public void setConfirmedTime(LocalDateTime confirmedTime) { this.confirmedTime = confirmedTime; }
    }

    public static class ConfirmResult {
        private final boolean success;
        private final String message;
        private final int remainingReminders;

        public ConfirmResult(boolean success, String message, int remainingReminders) {
            this.success = success;
            this.message = message;
            this.remainingReminders = remainingReminders;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getRemainingReminders() { return remainingReminders; }
    }

    @Transactional
    public void createRemindersForMeeting(String meetingId, List<String> importantAttendeeIds, List<String> vipAttendeeIds) {
        log.info("为会议创建提醒: meetingId={}", meetingId);

        Meeting meeting = meetingRepository.findByMeetingId(meetingId)
                .orElseThrow(() -> new RuntimeException("会议不存在: " + meetingId));

        List<Attendee> attendees = attendeeRepository.findByMeetingId(meetingId);
        Set<String> importantIds = new HashSet<>(importantAttendeeIds != null ? importantAttendeeIds : Collections.emptyList());
        Set<String> vipIds = new HashSet<>(vipAttendeeIds != null ? vipAttendeeIds : Collections.emptyList());

        for (Attendee attendee : attendees) {
            if ("declined".equals(attendee.getAttendeeStatus())) {
                continue;
            }

            String importance = determineImportance(attendee.getUserId(), importantIds, vipIds);

            ReminderConfig.ReminderStrategyConfig strategy = reminderConfig.getStrategyForImportance(importance);

            Reminder reminder = Reminder.builder()
                    .reminderId(IdGenerator.generateReminderId())
                    .meetingId(meetingId)
                    .reminderType("meeting_start")
                    .reminderTime(meeting.getMeetingStart().minusMinutes(30))
                    .reminderStatus("pending")
                    .reminderContent(String.format("会议提醒：您有一个会议'%s'将在30分钟后开始（级别：%s）",
                            meeting.getMeetingTopic(), getImportanceName(importance)))
                    .build();

            reminderRepository.save(reminder);

            String statusKey = meetingId + "_" + attendee.getUserId();
            reminderStatusMap.put(statusKey,
                    new ReminderStatus(reminder.getReminderId(), meetingId, attendee.getUserId(), importance));

            log.info("创建提醒: reminderId={}, meetingId={}, userId={}, importance={}, strategy={}",
                    reminder.getReminderId(), meetingId, attendee.getUserId(), importance, strategy.getDescription());
        }
    }

    @Transactional
    public void createRemindersForMeeting(String meetingId, List<String> importantAttendeeIds) {
        createRemindersForMeeting(meetingId, importantAttendeeIds, Collections.emptyList());
    }

    private String determineImportance(String userId, Set<String> importantIds, Set<String> vipIds) {
        if (vipIds.contains(userId)) {
            return IMPORTANCE_VIP;
        } else if (importantIds.contains(userId)) {
            return IMPORTANCE_IMPORTANT;
        }
        return IMPORTANCE_NORMAL;
    }

    private String getImportanceName(String importance) {
        switch (importance) {
            case IMPORTANCE_VIP: return "VIP";
            case IMPORTANCE_IMPORTANT: return "重要";
            default: return "普通";
        }
    }

    public int getRequiredConfirmCount(String importance) {
        return reminderConfig.getRequiredConfirmCount(importance);
    }

    public int getMaxReminderCount(String importance) {
        return reminderConfig.getMaxReminderCount(importance);
    }

    public long getReminderIntervalMinutes(String importance) {
        return reminderConfig.getReminderIntervalMinutes(importance);
    }

    @Transactional
    public ConfirmResult processReminderConfirm(String meetingId, String userId, String attendeeStatus) {
        log.info("处理提醒确认: meetingId={}, userId={}, status={}", meetingId, userId, attendeeStatus);

        String statusKey = meetingId + "_" + userId;
        ReminderStatus status = reminderStatusMap.get(statusKey);

        if (status == null) {
            return new ConfirmResult(false, "未找到对应的提醒记录", 0);
        }

        if ("confirmed".equals(attendeeStatus) || "declined".equals(attendeeStatus)) {
            status.setConfirmed(true);
            status.setConfirmedTime(LocalDateTime.now());

            log.info("提醒确认完成: meetingId={}, userId={}, status={}, sentCount={}, importance={}",
                    meetingId, userId, attendeeStatus, status.getSentCount(), status.getImportance());

            return new ConfirmResult(true, "确认成功", 0);
        }

        ReminderConfig.ReminderStrategyConfig strategy = reminderConfig.getStrategyForImportance(status.getImportance());
        int remaining = strategy.getRequiredConfirmCount() - status.getSentCount();
        return new ConfirmResult(false, "等待确认中", Math.max(0, remaining));
    }

    @Transactional
    public boolean sendReminderToAttendee(String meetingId, String userId) {
        String statusKey = meetingId + "_" + userId;
        ReminderStatus status = reminderStatusMap.get(statusKey);

        if (status == null || status.isConfirmed()) {
            return false;
        }

        ReminderConfig.ReminderStrategyConfig strategy = reminderConfig.getStrategyForImportance(status.getImportance());

        if (status.getSentCount() >= strategy.getMaxReminderCount()) {
            log.warn("提醒次数已达上限: meetingId={}, userId={}, importance={}, maxCount={}",
                    meetingId, userId, status.getImportance(), strategy.getMaxReminderCount());
            return false;
        }

        status.incrementSentCount();
        status.setLastSentTime(LocalDateTime.now());

        log.info("发送提醒: meetingId={}, userId={}, sentCount={}/{}, importance={}, interval={}min",
                meetingId, userId, status.getSentCount(),
                strategy.getMaxReminderCount(), status.getImportance(),
                strategy.getReminderIntervalMinutes());

        return true;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void retryPendingReminders() {
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, ReminderStatus> entry : reminderStatusMap.entrySet()) {
            ReminderStatus status = entry.getValue();

            if (status.isConfirmed()) {
                continue;
            }

            ReminderConfig.ReminderStrategyConfig strategy = reminderConfig.getStrategyForImportance(status.getImportance());

            if (status.getSentCount() >= strategy.getMaxReminderCount()) {
                continue;
            }

            if (status.getLastSentTime() == null) {
                sendReminderToAttendee(status.getMeetingId(), status.getUserId());
            } else {
                LocalDateTime nextSendTime = status.getLastSentTime().plusMinutes(strategy.getReminderIntervalMinutes());
                if (now.isAfter(nextSendTime)) {
                    sendReminderToAttendee(status.getMeetingId(), status.getUserId());
                }
            }
        }
    }

    public ReminderStatus getReminderStatus(String meetingId, String userId) {
        String statusKey = meetingId + "_" + userId;
        return reminderStatusMap.get(statusKey);
    }

    public int getSentCount(String meetingId, String userId) {
        ReminderStatus status = getReminderStatus(meetingId, userId);
        return status != null ? status.getSentCount() : 0;
    }

    public boolean isConfirmed(String meetingId, String userId) {
        ReminderStatus status = getReminderStatus(meetingId, userId);
        return status != null && status.isConfirmed();
    }

    public void clearReminderStatus(String meetingId) {
        reminderStatusMap.entrySet().removeIf(entry -> entry.getValue().getMeetingId().equals(meetingId));
        log.info("清除会议提醒状态: meetingId={}", meetingId);
    }

    public Map<String, Integer> getUnconfirmedStats(String meetingId) {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", 0);
        stats.put("confirmed", 0);
        stats.put("pending", 0);
        stats.put("maxedOut", 0);
        stats.put("vipTotal", 0);
        stats.put("importantTotal", 0);
        stats.put("normalTotal", 0);

        for (ReminderStatus status : reminderStatusMap.values()) {
            if (!meetingId.equals(status.getMeetingId())) {
                continue;
            }

            stats.put("total", stats.get("total") + 1);

            switch (status.getImportance()) {
                case IMPORTANCE_VIP:
                    stats.put("vipTotal", stats.get("vipTotal") + 1);
                    break;
                case IMPORTANCE_IMPORTANT:
                    stats.put("importantTotal", stats.get("importantTotal") + 1);
                    break;
                default:
                    stats.put("normalTotal", stats.get("normalTotal") + 1);
            }

            if (status.isConfirmed()) {
                stats.put("confirmed", stats.get("confirmed") + 1);
            } else {
                ReminderConfig.ReminderStrategyConfig strategy = reminderConfig.getStrategyForImportance(status.getImportance());
                if (status.getSentCount() >= strategy.getMaxReminderCount()) {
                    stats.put("maxedOut", stats.get("maxedOut") + 1);
                } else {
                    stats.put("pending", stats.get("pending") + 1);
                }
            }
        }

        return stats;
    }

    public Map<String, ReminderConfig.ReminderStrategyConfig> getAllStrategies() {
        return reminderConfig.getStrategyConfigs();
    }

    public void addOrUpdateStrategy(String importance, ReminderConfig.ReminderStrategyConfig config) {
        reminderConfig.addOrUpdateStrategy(importance, config);
        log.info("添加/更新提醒策略: importance={}, requiredConfirm={}, maxReminder={}, interval={}min",
                importance, config.getRequiredConfirmCount(),
                config.getMaxReminderCount(), config.getReminderIntervalMinutes());
    }

    public void removeStrategy(String importance) {
        reminderConfig.removeStrategy(importance);
        log.info("移除提醒策略: importance={}", importance);
    }
}
