package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.dto.AnswerReminderRequest;
import com.survey.entity.AnswerReminderRecord;
import com.survey.entity.AnswerRecord;
import com.survey.entity.PublishRecord;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.AnswerReminderRecordRepository;
import com.survey.repository.AnswerRecordRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerReminderService {

    private final AnswerReminderRecordRepository reminderRepository;
    private final AnswerRecordRepository answerRecordRepository;
    private final PublishService publishService;
    private final SurveyService surveyService;
    private final HistoryService historyService;

    @Transactional
    public List<AnswerReminderRecord> createReminders(AnswerReminderRequest request) {
        log.info("创建答卷提醒，发布ID: {}", request.getPublishId());

        PublishRecord publishRecord = publishService.getPublishRecord(request.getPublishId());

        Survey survey = surveyService.findSurvey(publishRecord.getSurveyId())
                .orElseThrow(() -> SurveyException.surveyNotFound(publishRecord.getSurveyId()));

        if (!SurveyConstants.SURVEY_STATUS_PUBLISHED.equals(survey.getSurveyStatus())) {
            throw new SurveyException(400, "问卷未发布，无法创建提醒: " + survey.getSurveyStatus());
        }

        List<String> targetUserIds = request.getTargetUserIds() != null 
                ? request.getTargetUserIds() 
                : new ArrayList<>();
        List<String> targetEmails = request.getTargetEmails() != null 
                ? request.getTargetEmails() 
                : new ArrayList<>();

        List<AnswerReminderRecord> reminderRecords = new ArrayList<>();

        for (String userId : targetUserIds) {
            if (!reminderRepository.existsByPublishIdAndUserId(request.getPublishId(), userId)) {
                AnswerReminderRecord reminder = createReminderRecord(
                        request.getPublishId(),
                        publishRecord.getSurveyId(),
                        userId,
                        null,
                        request.getMaxReminderCount()
                );
                reminderRecords.add(reminderRepository.save(reminder));
            }
        }

        for (String email : targetEmails) {
            if (!reminderRepository.existsByPublishIdAndUserEmail(request.getPublishId(), email)) {
                AnswerReminderRecord reminder = createReminderRecord(
                        request.getPublishId(),
                        publishRecord.getSurveyId(),
                        null,
                        email,
                        request.getMaxReminderCount()
                );
                reminderRecords.add(reminderRepository.save(reminder));
            }
        }

        historyService.recordSurveyHistory(publishRecord.getSurveyId(), "CREATE_REMINDER",
                "创建答卷提醒，发布ID: " + request.getPublishId() + ", 提醒数量: " + reminderRecords.size(), null);

        log.info("答卷提醒创建完成，数量: {}", reminderRecords.size());
        return reminderRecords;
    }

    private AnswerReminderRecord createReminderRecord(String publishId, String surveyId, 
            String userId, String email, Integer maxReminderCount) {
        AnswerReminderRecord reminder = new AnswerReminderRecord();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setSurveyId(surveyId);
        reminder.setPublishId(publishId);
        reminder.setUserId(userId);
        reminder.setUserEmail(email);
        reminder.setReminderStatus(SurveyConstants.ANSWER_REMINDER_PENDING);
        reminder.setReminderCount(0);
        reminder.setMaxReminderCount(maxReminderCount != null 
                ? maxReminderCount 
                : SurveyConstants.ANSWER_REMINDER_MAX);
        reminder.setCreatedAt(LocalDateTime.now());
        return reminder;
    }

    @Scheduled(fixedRate = 60000)
    public void checkAndSendReminders() {
        log.debug("检查待发送的答卷提醒...");

        List<AnswerReminderRecord> pendingReminders = reminderRepository.findByReminderStatus(
                SurveyConstants.ANSWER_REMINDER_PENDING
        );

        for (AnswerReminderRecord reminder : pendingReminders) {
            if (shouldSendReminder(reminder) && !hasUserAnswered(reminder)) {
                sendReminder(reminder);
            }
        }
    }

    private boolean shouldSendReminder(AnswerReminderRecord reminder) {
        if (reminder.getReminderCount() >= reminder.getMaxReminderCount()) {
            return false;
        }
        if (reminder.getLastReminderTime() == null) {
            return reminder.getCreatedAt().plusHours(1).isBefore(LocalDateTime.now());
        }
        return reminder.getLastReminderTime().plusHours(1).isBefore(LocalDateTime.now());
    }

    private boolean hasUserAnswered(AnswerReminderRecord reminder) {
        if (reminder.getUserId() != null) {
            List<AnswerRecord> records = answerRecordRepository.findBySurveyIdAndUserId(
                    reminder.getSurveyId(),
                    reminder.getUserId()
            );
            return !records.isEmpty();
        }
        return false;
    }

    @Transactional
    public void sendReminder(AnswerReminderRecord reminder) {
        if (reminder.getReminderCount() >= reminder.getMaxReminderCount()) {
            reminder.setReminderStatus(SurveyConstants.ANSWER_REMINDER_SENT);
            reminderRepository.save(reminder);
            log.warn("提醒次数已达上限，停止发送，提醒ID: {}", reminder.getReminderId());
            return;
        }

        reminder.setReminderCount(reminder.getReminderCount() + 1);
        reminder.setLastReminderTime(LocalDateTime.now());
        reminder.setUpdatedAt(LocalDateTime.now());

        if (reminder.getReminderCount() >= reminder.getMaxReminderCount()) {
            reminder.setReminderStatus(SurveyConstants.ANSWER_REMINDER_SENT);
        }

        sendReminderNotification(reminder);

        AnswerReminderRecord saved = reminderRepository.save(reminder);

        historyService.recordPublishHistory(reminder.getPublishId(), "SEND_REMINDER",
                "发送答卷提醒，第" + reminder.getReminderCount() + "次，目标: " + 
                (reminder.getUserEmail() != null ? reminder.getUserEmail() : reminder.getUserId()), null);

        log.info("答卷提醒已发送，第{}次，提醒ID: {}", reminder.getReminderCount(), saved.getReminderId());
    }

    private void sendReminderNotification(AnswerReminderRecord reminder) {
        String target = reminder.getUserEmail() != null 
                ? reminder.getUserEmail() 
                : (reminder.getUserId() != null ? reminder.getUserId() : "未知用户");

        String surveyName = "未知问卷";
        try {
            Survey survey = surveyService.findSurvey(reminder.getSurveyId()).orElse(null);
            if (survey != null) {
                surveyName = survey.getSurveyName();
            }
        } catch (Exception e) {
            log.warn("获取问卷信息失败", e);
        }

        log.info("发送答卷提醒通知，目标: {}, 问卷: {}, 提醒次数: {}/{}", 
                target, surveyName, reminder.getReminderCount(), reminder.getMaxReminderCount());

        if (reminder.getUserEmail() != null) {
            log.info("已发送邮件提醒到: {}", reminder.getUserEmail());
        } else {
            log.info("已发送系统消息提醒到用户: {}", reminder.getUserId());
        }
    }

    @Transactional
    public void markReminderCompleted(String reminderId) {
        AnswerReminderRecord reminder = reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new SurveyException(404, "提醒记录不存在: " + reminderId));

        reminder.setReminderStatus(SurveyConstants.ANSWER_REMINDER_COMPLETED);
        reminder.setCompletedAt(LocalDateTime.now());
        reminder.setUpdatedAt(LocalDateTime.now());

        reminderRepository.save(reminder);

        historyService.recordPublishHistory(reminder.getPublishId(), "REMINDER_COMPLETED",
                "答卷提醒已完成，用户已提交答卷", null);

        log.info("答卷提醒标记为已完成，提醒ID: {}", reminderId);
    }

    @Transactional
    public void markUserRemindersCompleted(String surveyId, String userId, String userEmail) {
        List<AnswerReminderRecord> reminders = reminderRepository.findBySurveyIdAndReminderStatus(
                surveyId,
                SurveyConstants.ANSWER_REMINDER_PENDING
        );

        for (AnswerReminderRecord reminder : reminders) {
            if (userId != null && userId.equals(reminder.getUserId())) {
                markReminderCompleted(reminder.getReminderId());
            } else if (userEmail != null && userEmail.equals(reminder.getUserEmail())) {
                markReminderCompleted(reminder.getReminderId());
            }
        }
    }

    @Transactional
    public void forceSendReminder(String reminderId) {
        AnswerReminderRecord reminder = reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new SurveyException(404, "提醒记录不存在: " + reminderId));

        if (SurveyConstants.ANSWER_REMINDER_COMPLETED.equals(reminder.getReminderStatus())) {
            throw new SurveyException(400, "提醒已完成，无法重发: " + reminderId);
        }

        sendReminder(reminder);

        historyService.recordPublishHistory(reminder.getPublishId(), "FORCE_SEND_REMINDER",
                "强制发送答卷提醒", null);
    }

    public AnswerReminderRecord getReminder(String reminderId) {
        return reminderRepository.findByReminderId(reminderId)
                .orElseThrow(() -> new SurveyException(404, "提醒记录不存在: " + reminderId));
    }

    public List<AnswerReminderRecord> getRemindersBySurvey(String surveyId) {
        return reminderRepository.findBySurveyId(surveyId);
    }

    public List<AnswerReminderRecord> getRemindersByPublish(String publishId) {
        return reminderRepository.findByPublishId(publishId);
    }

    public List<AnswerReminderRecord> getPendingReminders(String surveyId) {
        return reminderRepository.findBySurveyIdAndReminderStatus(
                surveyId,
                SurveyConstants.ANSWER_REMINDER_PENDING
        );
    }

    public List<AnswerReminderRecord> getCompletedReminders(String surveyId) {
        return reminderRepository.findBySurveyIdAndReminderStatus(
                surveyId,
                SurveyConstants.ANSWER_REMINDER_COMPLETED
        );
    }

    @Transactional
    public void checkAndCompleteReminders(String surveyId) {
        List<AnswerReminderRecord> pendingReminders = reminderRepository.findBySurveyIdAndReminderStatus(
                surveyId,
                SurveyConstants.ANSWER_REMINDER_PENDING
        );

        List<AnswerRecord> answerRecords = answerRecordRepository.findBySurveyId(surveyId);
        Set<String> answeredUserIds = answerRecords.stream()
                .filter(r -> r.getUserId() != null)
                .map(AnswerRecord::getUserId)
                .collect(Collectors.toSet());

        for (AnswerReminderRecord reminder : pendingReminders) {
            if (reminder.getUserId() != null && answeredUserIds.contains(reminder.getUserId())) {
                markReminderCompleted(reminder.getReminderId());
            }
        }
    }
}
