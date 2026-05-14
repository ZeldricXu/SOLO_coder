package com.recruitment.service;

import com.recruitment.common.enums.InterviewStatus;
import com.recruitment.model.Interview;
import com.recruitment.model.Interviewer;
import com.recruitment.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewReminderService {

    private final InterviewRepository interviewRepository;
    private final CandidateService candidateService;
    private final InterviewerService interviewerService;

    private final ConcurrentHashMap<String, List<String>> sentReminders = new ConcurrentHashMap<>();

    private final AtomicInteger urgentReminderCount = new AtomicInteger(0);
    private final AtomicInteger normalReminderCount = new AtomicInteger(0);

    public enum UrgencyLevel {
        URGENT,
        NORMAL,
        LOW
    }

    @Async("interviewReminderExecutor")
    public void sendInterviewReminder(Interview interview) {
        log.info("InterviewReminder: 准备发送面试提醒, interviewId: {}", interview.getInterviewId());

        if (interview.getInterviewStatus() != InterviewStatus.SCHEDULED) {
            log.warn("InterviewReminder: 面试状态不是SCHEDULED，跳过提醒");
            return;
        }

        UrgencyLevel urgency = calculateUrgency(interview.getInterviewTime());

        int reminderFrequency = getReminderFrequency(urgency);

        for (int i = 0; i < reminderFrequency; i++) {
            sendReminderNotification(interview, urgency, i + 1);
        }

        if (urgency == UrgencyLevel.URGENT) {
            urgentReminderCount.incrementAndGet();
        } else {
            normalReminderCount.incrementAndGet();
        }

        recordReminder(interview.getInterviewId());

        log.info("InterviewReminder: 面试提醒发送完成, interviewId: {}, urgency: {}",
                interview.getInterviewId(), urgency);
    }

    public UrgencyLevel calculateUrgency(Instant interviewTime) {
        Duration duration = Duration.between(Instant.now(), interviewTime);
        long hours = duration.toHours();

        if (hours <= 24) {
            return UrgencyLevel.URGENT;
        } else if (hours <= 72) {
            return UrgencyLevel.NORMAL;
        } else {
            return UrgencyLevel.LOW;
        }
    }

    public int getReminderFrequency(UrgencyLevel urgency) {
        switch (urgency) {
            case URGENT:
                return 3;
            case NORMAL:
                return 1;
            case LOW:
            default:
                return 0;
        }
    }

    public void sendReminderNotification(Interview interview, UrgencyLevel urgency, int reminderNumber) {
        String interviewer;
        try {
            interviewer = interviewerService.getInterviewer(interview.getInterviewerId());
        } catch (RuntimeException e) {
            log.error("InterviewReminder: 获取面试官信息失败: {}", e.getMessage());
            return;
        }

        String message = buildReminderMessage(interview, interviewer, urgency, reminderNumber);
        log.info("InterviewReminder: [第{}次提醒] {}", reminderNumber, message);

        switch (urgency) {
            case URGENT:
                sendEmailNotification(message);
                sendSmsNotification(message);
                sendAppPushNotification(message);
                break;
            case NORMAL:
                sendEmailNotification(message);
                break;
            case LOW:
            default:
                break;
        }
    }

    private String buildReminderMessage(Interview interview, Interviewer interviewer,
                                     UrgencyLevel urgency, int reminderNumber) {
        return String.format(
                "【面试提醒-第%d次】面试时间: %s, 面试官: %s, 类型: %s, 紧急程度: %s",
                reminderNumber,
                interview.getInterviewTime(),
                interviewer.getInterviewerName(),
                interview.getInterviewType(),
                urgency
        );
    }

    protected void sendEmailNotification(String message) {
        log.info("InterviewReminder: [邮件] " + message);
    }

    protected void sendSmsNotification(String message) {
        log.info("InterviewReminder: [短信] " + message);
    }

    protected void sendAppPushNotification(String message) {
        log.info("InterviewReminder: [App推送] " + message);
    }

    private void recordReminder(String interviewId) {
        sentReminders.computeIfAbsent(interviewId, k -> new ArrayList<>());
        sentReminders.get(interviewId).add("sent_at_" + System.currentTimeMillis());
    }

    public boolean hasReminderSent(String interviewId) {
        return sentReminders.containsKey(interviewId) && !sentReminders.get(interviewId).isEmpty();
    }

    public int getSentReminderCount(String interviewId) {
        return sentReminders.containsKey(interviewId) ?
                sentReminders.get(interviewId).size() : 0;
    }

    public int getTotalUrgentReminders() {
        return urgentReminderCount.get();
    }

    public int getTotalNormalReminders() {
        return normalReminderCount.get();
    }

    public void resetCounters() {
        urgentReminderCount.set(0);
        normalReminderCount.set(0);
        sentReminders.clear();
    }

    @Async("interviewReminderExecutor")
    public void sendRemindersForAllScheduled() {
        log.info("InterviewReminder: 批量发送所有已安排面试的提醒");

        List<Interview> scheduledInterviews = interviewRepository.findByInterviewStatus(InterviewStatus.SCHEDULED);

        for (Interview interview : scheduledInterviews) {
            if (!hasReminderSent(interview.getInterviewId())) {
                sendInterviewReminder(interview);
            }
        }

        log.info("InterviewReminder: 批量提醒处理完成, 共处理 {} 个面试", scheduledInterviews.size());
    }
}
