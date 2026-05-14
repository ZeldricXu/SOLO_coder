package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.entity.HistoryRecord;
import com.survey.repository.HistoryRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryRecordRepository historyRecordRepository;

    public void recordSurveyHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_SURVEY, businessId, action, detail, operatorId);
    }

    public void recordPublishHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_PUBLISH, businessId, action, detail, operatorId);
    }

    public void recordAnswerHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_ANSWER, businessId, action, detail, operatorId);
    }

    public void recordReviewHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_REVIEW, businessId, action, detail, operatorId);
    }

    public void recordStatHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_STAT, businessId, action, detail, operatorId);
    }

    public void recordReportHistory(String businessId, String action, String detail, String operatorId) {
        createHistoryRecord(SurveyConstants.BUSINESS_TYPE_REPORT, businessId, action, detail, operatorId);
    }

    private void createHistoryRecord(String businessType, String businessId, String action, String detail, String operatorId) {
        HistoryRecord record = new HistoryRecord();
        record.setBusinessType(businessType);
        record.setBusinessId(businessId);
        record.setAction(action);
        record.setDetail(detail);
        record.setOperatorId(operatorId);
        record.setCreatedAt(LocalDateTime.now());
        historyRecordRepository.save(record);
        log.debug("记录历史: {} - {} - {}", businessType, businessId, action);
    }

    public List<HistoryRecord> getSurveyHistory(String surveyId) {
        return historyRecordRepository.findByBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                SurveyConstants.BUSINESS_TYPE_SURVEY, surveyId);
    }

    public List<HistoryRecord> getAnswerHistory(String answerId) {
        return historyRecordRepository.findByBusinessTypeAndBusinessIdOrderByCreatedAtDesc(
                SurveyConstants.BUSINESS_TYPE_ANSWER, answerId);
    }

    public List<HistoryRecord> getHistoryByType(String businessType) {
        return historyRecordRepository.findByBusinessTypeOrderByCreatedAtDesc(businessType);
    }

    public List<HistoryRecord> getHistory(String businessType, String businessId) {
        return historyRecordRepository.findByBusinessTypeAndBusinessIdOrderByCreatedAtDesc(businessType, businessId);
    }
}
