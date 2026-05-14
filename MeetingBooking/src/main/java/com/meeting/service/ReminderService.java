package com.meeting.service;

import com.meeting.dto.ReminderSendRequest;
import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.entity.Reminder;
import com.meeting.exception.MeetingException;
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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final MeetingRepository meetingRepository;
    private final AttendeeRepository attendeeRepository;
    private final StatsService statsService;
    private final HistoryService historyService;

    private static final String REMINDER_STATUS_PENDING = "pending";
    private static final String REMINDER_STATUS_SENT = "sent";
    private static final String REMINDER_STATUS_FAILED = "failed";

    private static final String REMINDER_TYPE_START = "meeting_start";
    private static final String REMINDER_TYPE_END = "meeting_end";
    private static final String REMINDER_TYPE_CANCEL = "meeting_cancel";

    public Reminder getReminderById(String reminderId) {
        return reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new MeetingException(404, "提醒记录不存在: " + reminderId));
    }

    public List<Reminder> getRemindersByMeetingId(String meetingId) {
        return reminderRepository.findByMeetingId(meetingId);
    }

    public List<Reminder> getPendingReminders() {
        return reminderRepository.findByReminderStatus(REMINDER_STATUS_PENDING);
    }

    @Transactional
    public Reminder createReminder(Reminder reminder) {
        if (reminder.getReminderId() == null || reminder.getReminderId().isEmpty()) {
            reminder.setReminderId(IdGenerator.generateReminderId());
        }
        if (reminder.getReminderStatus() == null || reminder.getReminderStatus().isEmpty()) {
            reminder.setReminderStatus(REMINDER_STATUS_PENDING);
        }

        log.info("创建提醒: reminderId={}, meetingId={}, type={}",
                reminder.getReminderId(), reminder.getMeetingId(), reminder.getReminderType());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createMeetingStartReminder(Meeting meeting, int minutesBefore) {
        LocalDateTime reminderTime = meeting.getMeetingStart().minusMinutes(minutesBefore);
        String content = String.format("会议提醒：您有一个会议'%s'将在%d分钟后开始",
                meeting.getMeetingTopic(), minutesBefore);

        Reminder reminder = Reminder.builder()
                .reminderId(IdGenerator.generateReminderId())
                .meetingId(meeting.getMeetingId())
                .reminderType(REMINDER_TYPE_START)
                .reminderTime(reminderTime)
                .reminderStatus(REMINDER_STATUS_PENDING)
                .reminderContent(content)
                .build();

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createMeetingEndReminder(Meeting meeting, int minutesBefore) {
        LocalDateTime reminderTime = meeting.getMeetingEnd().minusMinutes(minutesBefore);
        String content = String.format("会议提醒：会议'%s'将在%d分钟后结束",
                meeting.getMeetingTopic(), minutesBefore);

        Reminder reminder = Reminder.builder()
                .reminderId(IdGenerator.generateReminderId())
                .meetingId(meeting.getMeetingId())
                .reminderType(REMINDER_TYPE_END)
                .reminderTime(reminderTime)
                .reminderStatus(REMINDER_STATUS_PENDING)
                .reminderContent(content)
                .build();

        return reminderRepository.save(reminder);
    }

    @Transactional
    public void sendReminder(ReminderSendRequest request) {
        log.info("发送会议提醒: meetingId={}, type={}", request.getMeetingId(), request.getReminderType());

        Meeting meeting = meetingRepository.findByMeetingId(request.getMeetingId())
                .orElseThrow(() -> new MeetingException(404, "会议不存在: " + request.getMeetingId()));

        if ("cancelled".equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已取消，无法发送提醒");
        }

        List<Attendee> attendees = attendeeRepository.findByMeetingId(request.getMeetingId());

        String reminderType = request.getReminderType() != null ? request.getReminderType() : REMINDER_TYPE_START;
        String content = request.getReminderContent() != null ? request.getReminderContent() :
                String.format("会议提醒：%s", meeting.getMeetingTopic());

        for (Attendee attendee : attendees) {
            if (!"declined".equals(attendee.getAttendeeStatus())) {
                sendReminderToAttendee(meeting, attendee, reminderType, content);
            }
        }

        Reminder reminder = Reminder.builder()
                .reminderId(IdGenerator.generateReminderId())
                .meetingId(meeting.getMeetingId())
                .reminderType(reminderType)
                .reminderTime(LocalDateTime.now())
                .reminderStatus(REMINDER_STATUS_SENT)
                .sentTime(LocalDateTime.now())
                .reminderContent(content)
                .build();

        reminderRepository.save(reminder);
        statsService.incrementReminderSent(meeting.getMeetingStart());
        historyService.recordReminderSent(reminder, "system");

        log.info("会议提醒发送完成: meetingId={}, attendeeCount={}", meeting.getMeetingId(), attendees.size());
    }

    private void sendReminderToAttendee(Meeting meeting, Attendee attendee, String type, String content) {
        log.info("发送{}给参会人员: meetingId={}, userId={}, userName={}",
                type, meeting.getMeetingId(), attendee.getUserId(), attendee.getUserName());
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void processPendingReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Reminder> pendingReminders = reminderRepository.findPendingRemindersToSend(now);

        for (Reminder reminder : pendingReminders) {
            try {
                sendPendingReminder(reminder);
                reminder.setReminderStatus(REMINDER_STATUS_SENT);
                reminder.setSentTime(LocalDateTime.now());
                reminderRepository.save(reminder);
            } catch (Exception e) {
                log.error("发送提醒失败: reminderId={}", reminder.getReminderId(), e);
                reminder.setReminderStatus(REMINDER_STATUS_FAILED);
                reminderRepository.save(reminder);
            }
        }
    }

    private void sendPendingReminder(Reminder reminder) {
        Meeting meeting = meetingRepository.findByMeetingId(reminder.getMeetingId())
                .orElse(null);

        if (meeting == null) {
            log.warn("会议不存在，跳过提醒: meetingId={}", reminder.getMeetingId());
            return;
        }

        List<Attendee> attendees = attendeeRepository.findByMeetingId(reminder.getMeetingId());
        for (Attendee attendee : attendees) {
            if (!"declined".equals(attendee.getAttendeeStatus())) {
                sendReminderToAttendee(meeting, attendee, reminder.getReminderType(),
                        reminder.getReminderContent());
            }
        }

        statsService.incrementReminderSent(meeting.getMeetingStart());
        historyService.recordReminderSent(reminder, "system");
        log.info("定时发送提醒完成: reminderId={}, meetingId={}",
                reminder.getReminderId(), reminder.getMeetingId());
    }

    @Transactional
    public Reminder updateReminderStatus(String reminderId, String status) {
        Reminder reminder = getReminderById(reminderId);
        reminder.setReminderStatus(status);
        if (REMINDER_STATUS_SENT.equals(status)) {
            reminder.setSentTime(LocalDateTime.now());
        }
        return reminderRepository.save(reminder);
    }

    @Transactional
    public void deleteReminder(String reminderId) {
        Reminder reminder = getReminderById(reminderId);
        reminderRepository.delete(reminder);
        log.info("删除提醒: reminderId={}", reminderId);
    }

    public List<Reminder> getAllReminders() {
        return reminderRepository.findAll();
    }
}
