package com.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.common.SurveyConstants;
import com.survey.dto.PublishConfirmRequest;
import com.survey.dto.PublishRequest;
import com.survey.dto.PublishResponse;
import com.survey.entity.PublishRecord;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.PublishRecordRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishService {

    private final PublishRecordRepository publishRecordRepository;
    private final SurveyService surveyService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public PublishResponse publishSurvey(PublishRequest request) {
        log.info("发布问卷: {}", request.getSurveyId());

        Survey survey = surveyService.findSurvey(request.getSurveyId())
                .orElseThrow(() -> SurveyException.surveyNotFound(request.getSurveyId()));

        if (SurveyConstants.SURVEY_STATUS_CLOSED.equals(survey.getSurveyStatus())) {
            throw SurveyException.surveyClosed(request.getSurveyId());
        }

        if (SurveyConstants.SURVEY_STATUS_EXPIRED.equals(survey.getSurveyStatus())) {
            throw SurveyException.surveyExpired(request.getSurveyId());
        }

        if (!surveyService.isValidForPublish(request.getSurveyId())) {
            throw new SurveyException(400, "问卷状态不允许发布: " + survey.getSurveyStatus());
        }

        PublishRecord record = new PublishRecord();
        record.setPublishId(IdGenerator.generatePublishId());
        record.setSurveyId(request.getSurveyId());
        record.setPublishChannel(request.getPublishChannel());
        record.setPublishRange(request.getPublishRange());
        record.setPublishCount(calculatePublishCount(request.getPublishRange()));
        record.setPublishLink(generatePublishLink(record.getPublishId()));

        if (Boolean.TRUE.equals(request.getNeedConfirm())) {
            record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_PENDING_CONFIRM);
            record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_PENDING);
        } else {
            record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_PUBLISHED);
            record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_CONFIRMED);
        }

        record.setPublishTime(LocalDateTime.now());
        record.setMaxRetryCount(request.getMaxRetryCount() != null 
                ? request.getMaxRetryCount() 
                : SurveyConstants.PUBLISH_RETRY_MAX);
        record.setRetryCount(0);

        if (request.getTargetEmails() != null && !request.getTargetEmails().isEmpty()) {
            try {
                record.setTargetEmails(objectMapper.writeValueAsString(request.getTargetEmails()));
            } catch (JsonProcessingException e) {
                log.warn("序列化目标邮箱失败", e);
            }
        }
        if (request.getTargetUserIds() != null && !request.getTargetUserIds().isEmpty()) {
            try {
                record.setTargetUserIds(objectMapper.writeValueAsString(request.getTargetUserIds()));
            } catch (JsonProcessingException e) {
                log.warn("序列化目标用户ID失败", e);
            }
        }

        PublishRecord saved = publishRecordRepository.save(record);

        if (SurveyConstants.PUBLISH_STATUS_PENDING_CONFIRM.equals(saved.getPublishStatus())) {
            sendPublishNotification(saved);
            log.info("发布通知已发送，等待确认: {}", saved.getPublishId());
        } else {
            surveyService.updateSurveyStatus(request.getSurveyId(), SurveyConstants.SURVEY_STATUS_PUBLISHED);
            log.info("问卷直接发布成功，无需确认: {}", saved.getPublishId());
        }

        historyService.recordPublishHistory(saved.getPublishId(), "PUBLISH_SURVEY",
                "发布问卷: " + survey.getSurveyName() + ", 渠道: " + request.getPublishChannel() + ", 范围: " + request.getPublishRange(), null);

        if (SurveyConstants.PUBLISH_STATUS_PUBLISHED.equals(saved.getPublishStatus())) {
            historyService.recordSurveyHistory(request.getSurveyId(), "PUBLISHED",
                    "问卷已发布，发布ID: " + saved.getPublishId(), null);
        } else {
            historyService.recordSurveyHistory(request.getSurveyId(), "PUBLISH_PENDING",
                    "问卷发布通知已发送，等待确认，发布ID: " + saved.getPublishId(), null);
        }

        return new PublishResponse(saved.getPublishId(), saved.getPublishStatus(), saved.getPublishLink());
    }

    @Transactional
    public PublishRecord confirmPublish(PublishConfirmRequest request) {
        log.info("确认发布，发布ID: {}, 状态: {}", request.getPublishId(), request.getConfirmStatus());

        PublishRecord record = publishRecordRepository.findByPublishId(request.getPublishId())
                .orElseThrow(() -> new SurveyException(404, "发布记录不存在: " + request.getPublishId()));

        if (!SurveyConstants.PUBLISH_STATUS_PENDING_CONFIRM.equals(record.getPublishStatus())) {
            throw new SurveyException(400, "发布状态不允许确认，当前状态: " + record.getPublishStatus());
        }

        if (SurveyConstants.PUBLISH_CONFIRM_CONFIRMED.equals(request.getConfirmStatus())) {
            record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_CONFIRMED);
            record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_PUBLISHED);
            record.setConfirmedAt(LocalDateTime.now());

            surveyService.updateSurveyStatus(record.getSurveyId(), SurveyConstants.SURVEY_STATUS_PUBLISHED);

            historyService.recordPublishHistory(request.getPublishId(), "CONFIRM_PUBLISH",
                    "发布确认成功，确认人: " + (request.getConfirmedBy() != null ? request.getConfirmedBy() : "系统"), null);
            historyService.recordSurveyHistory(record.getSurveyId(), "PUBLISHED",
                    "问卷已确认发布，发布ID: " + record.getPublishId(), null);

            log.info("发布确认成功: {}", request.getPublishId());
        } else if (SurveyConstants.PUBLISH_CONFIRM_FAILED.equals(request.getConfirmStatus())) {
            if (record.getRetryCount() < record.getMaxRetryCount()) {
                record.setRetryCount(record.getRetryCount() + 1);
                record.setLastRetryTime(LocalDateTime.now());
                record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_PENDING);

                sendPublishNotification(record);

                historyService.recordPublishHistory(request.getPublishId(), "RETRY_PUBLISH",
                        "发布确认失败，第" + record.getRetryCount() + "次重试，原因: " + 
                        (request.getConfirmMessage() != null ? request.getConfirmMessage() : "未知"), null);

                log.info("发布确认失败，第{}次重试，发布ID: {}", record.getRetryCount(), request.getPublishId());
            } else {
                record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_FAILED);
                record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_FAILED);

                historyService.recordPublishHistory(request.getPublishId(), "PUBLISH_FAILED",
                        "发布确认失败，已达最大重试次数，原因: " + 
                        (request.getConfirmMessage() != null ? request.getConfirmMessage() : "未知"), null);

                log.warn("发布确认失败，已达最大重试次数，发布ID: {}", request.getPublishId());
            }
        } else {
            throw new SurveyException(400, "无效的确认状态: " + request.getConfirmStatus());
        }

        return publishRecordRepository.save(record);
    }

    @Scheduled(fixedRate = 60000)
    public void retryPendingConfirms() {
        log.debug("检查待确认的发布记录...");

        List<PublishRecord> pendingRecords = publishRecordRepository.findByPublishStatusAndConfirmStatus(
                SurveyConstants.PUBLISH_STATUS_PENDING_CONFIRM,
                SurveyConstants.PUBLISH_CONFIRM_PENDING
        );

        for (PublishRecord record : pendingRecords) {
            if (shouldRetry(record)) {
                retryPublish(record);
            }
        }
    }

    private boolean shouldRetry(PublishRecord record) {
        if (record.getRetryCount() >= record.getMaxRetryCount()) {
            return false;
        }
        if (record.getLastRetryTime() == null) {
            return record.getPublishTime().plusMinutes(1).isBefore(LocalDateTime.now());
        }
        return record.getLastRetryTime().plusMinutes(1).isBefore(LocalDateTime.now());
    }

    @Transactional
    public void retryPublish(PublishRecord record) {
        if (record.getRetryCount() >= record.getMaxRetryCount()) {
            record.setConfirmStatus(SurveyConstants.PUBLISH_CONFIRM_FAILED);
            record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_FAILED);
            publishRecordRepository.save(record);

            historyService.recordPublishHistory(record.getPublishId(), "PUBLISH_FAILED",
                    "发布确认失败，已达最大重试次数", null);

            log.warn("发布确认失败，已达最大重试次数，发布ID: {}", record.getPublishId());
            return;
        }

        record.setRetryCount(record.getRetryCount() + 1);
        record.setLastRetryTime(LocalDateTime.now());

        sendPublishNotification(record);

        publishRecordRepository.save(record);

        historyService.recordPublishHistory(record.getPublishId(), "AUTO_RETRY_PUBLISH",
                "自动重试发送发布通知，第" + record.getRetryCount() + "次", null);

        log.info("自动重试发布确认，第{}次，发布ID: {}", record.getRetryCount(), record.getPublishId());
    }

    @Transactional
    public void cancelPublish(String publishId) {
        log.info("取消发布: {}", publishId);

        PublishRecord record = publishRecordRepository.findByPublishId(publishId)
                .orElseThrow(() -> new SurveyException(404, "发布记录不存在: " + publishId));

        record.setPublishStatus(SurveyConstants.PUBLISH_STATUS_CANCELLED);
        publishRecordRepository.save(record);

        historyService.recordPublishHistory(publishId, "CANCEL_PUBLISH",
                "取消发布，问卷ID: " + record.getSurveyId(), null);
    }

    @Transactional
    public void resendNotification(String publishId) {
        log.info("重新发送发布通知: {}", publishId);

        PublishRecord record = publishRecordRepository.findByPublishId(publishId)
                .orElseThrow(() -> new SurveyException(404, "发布记录不存在: " + publishId));

        if (SurveyConstants.PUBLISH_STATUS_CANCELLED.equals(record.getPublishStatus())
                || SurveyConstants.PUBLISH_STATUS_FAILED.equals(record.getPublishStatus())) {
            throw new SurveyException(400, "发布已取消或失败，无法重发: " + publishId);
        }

        sendPublishNotification(record);

        historyService.recordPublishHistory(publishId, "RESEND_NOTIFICATION",
                "重新发送发布通知", null);
    }

    private int calculatePublishCount(String publishRange) {
        return switch (publishRange) {
            case SurveyConstants.PUBLISH_RANGE_ALL -> 1000;
            case SurveyConstants.PUBLISH_RANGE_TARGET -> 500;
            case SurveyConstants.PUBLISH_RANGE_DEPARTMENT -> 100;
            default -> 0;
        };
    }

    private String generatePublishLink(String publishId) {
        return "http://localhost:8080/survey/" + publishId;
    }

    private void sendPublishNotification(PublishRecord record) {
        log.info("发送发布通知，渠道: {}, 链接: {}", record.getPublishChannel(), record.getPublishLink());
        
        List<String> targetEmails = parseTargetList(record.getTargetEmails());
        List<String> targetUserIds = parseTargetList(record.getTargetUserIds());

        if (SurveyConstants.PUBLISH_CHANNEL_EMAIL.equals(record.getPublishChannel())) {
            if (targetEmails != null && !targetEmails.isEmpty()) {
                log.info("已发送邮件通知到 {} 个目标用户: {}", targetEmails.size(), 
                        targetEmails.stream().limit(5).collect(Collectors.joining(", ")));
            } else {
                log.info("已发送邮件通知到 {} 个用户", record.getPublishCount());
            }
        } else if (SurveyConstants.PUBLISH_CHANNEL_LINK.equals(record.getPublishChannel())) {
            log.info("已生成分享链接: {}", record.getPublishLink());
        } else if (SurveyConstants.PUBLISH_CHANNEL_SMS.equals(record.getPublishChannel())) {
            if (targetUserIds != null && !targetUserIds.isEmpty()) {
                log.info("已发送短信通知到 {} 个目标用户: {}", targetUserIds.size(),
                        targetUserIds.stream().limit(5).collect(Collectors.joining(", ")));
            } else {
                log.info("已发送短信通知到 {} 个用户", record.getPublishCount());
            }
        } else if (SurveyConstants.PUBLISH_CHANNEL_WECHAT.equals(record.getPublishChannel())) {
            log.info("已发送微信通知，链接: {}", record.getPublishLink());
        }
    }

    private List<String> parseTargetList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonStr, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.warn("解析目标列表失败: {}", jsonStr, e);
            return null;
        }
    }

    public PublishRecord getPublishRecord(String publishId) {
        return publishRecordRepository.findByPublishId(publishId)
                .orElseThrow(() -> new SurveyException(404, "发布记录不存在: " + publishId));
    }

    public List<PublishRecord> getPublishRecordsBySurvey(String surveyId) {
        return publishRecordRepository.findBySurveyId(surveyId);
    }

    public List<PublishRecord> getActivePublishRecords(String surveyId) {
        return publishRecordRepository.findBySurveyIdAndPublishStatus(surveyId, SurveyConstants.PUBLISH_STATUS_PUBLISHED);
    }

    public List<PublishRecord> getPendingConfirmRecords() {
        return publishRecordRepository.findByPublishStatus(SurveyConstants.PUBLISH_STATUS_PENDING_CONFIRM);
    }

    public List<PublishRecord> getConfirmedPublishRecords(String surveyId) {
        return publishRecordRepository.findBySurveyIdAndConfirmStatus(surveyId, SurveyConstants.PUBLISH_CONFIRM_CONFIRMED);
    }
}
