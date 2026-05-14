package com.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.common.SurveyConstants;
import com.survey.dto.StatQueryResponse;
import com.survey.entity.AnswerData;
import com.survey.entity.AnswerRecord;
import com.survey.entity.Question;
import com.survey.entity.StatRecord;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.AnswerDataRepository;
import com.survey.repository.StatRecordRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final StatRecordRepository statRecordRepository;
    private final AnswerService answerService;
    private final AnswerDataRepository answerDataRepository;
    private final SurveyService surveyService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public StatRecord updateStatistics(String surveyId) {
        log.info("更新统计数据，问卷ID: {}", surveyId);

        Survey survey = surveyService.findSurvey(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        StatRecord statRecord = statRecordRepository.findBySurveyId(surveyId)
                .orElseGet(() -> createNewStatRecord(surveyId));

        long totalCount = answerService.getAnswerCount(surveyId);
        long reviewedCount;

        if (Boolean.TRUE.equals(survey.getNeedReview())) {
            reviewedCount = answerService.getAnswerCountByStatus(surveyId, SurveyConstants.ANSWER_STATUS_REVIEWED);
        } else {
            reviewedCount = totalCount;
        }

        int questionCount = surveyService.getSurveyQuestions(surveyId).size();
        double completionRate = questionCount > 0 ? (double) reviewedCount / questionCount : 0;
        if (totalCount > 0) {
            completionRate = (double) reviewedCount / totalCount;
        }

        statRecord.setStatAnswerCount((int) totalCount);
        statRecord.setStatReviewedCount((int) reviewedCount);
        statRecord.setStatCompletionRate(Math.min(completionRate, 1.0));
        statRecord.setStatQuestionStat(calculateQuestionStatistics(surveyId));
        statRecord.setUpdatedAt(LocalDateTime.now());

        StatRecord saved = statRecordRepository.save(statRecord);

        historyService.recordStatHistory(saved.getStatId(), "UPDATE_STAT",
                "更新统计数据，问卷ID: " + surveyId + ", 答卷数: " + totalCount, null);

        log.info("统计数据更新完成: {}", saved.getStatId());
        return saved;
    }

    private StatRecord createNewStatRecord(String surveyId) {
        StatRecord record = new StatRecord();
        record.setStatId(IdGenerator.generateStatId());
        record.setSurveyId(surveyId);
        record.setStatAnswerCount(0);
        record.setStatReviewedCount(0);
        record.setStatCompletionRate(0.0);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    private String calculateQuestionStatistics(String surveyId) {
        Map<String, Map<String, Integer>> questionStats = new HashMap<>();

        List<Question> questions = surveyService.getSurveyQuestions(surveyId);
        for (Question question : questions) {
            List<AnswerData> answers = answerDataRepository.findByQuestionId(question.getQuestionId());
            Map<String, Integer> distribution = new HashMap<>();
            for (AnswerData answer : answers) {
                String value = answer.getAnswerValue();
                if (value != null && !value.isEmpty()) {
                    distribution.merge(value, 1, Integer::sum);
                }
            }
            questionStats.put(question.getQuestionId(), distribution);
        }

        try {
            return objectMapper.writeValueAsString(questionStats);
        } catch (JsonProcessingException e) {
            log.error("序列化统计数据失败", e);
            return "{}";
        }
    }

    public StatQueryResponse getStatistics(String surveyId) {
        log.info("查询统计数据，问卷ID: {}", surveyId);

        Survey survey = surveyService.findSurvey(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        Optional<StatRecord> statRecordOpt = statRecordRepository.findBySurveyId(surveyId);
        if (statRecordOpt.isEmpty()) {
            StatRecord updated = updateStatistics(surveyId);
            return convertToResponse(updated);
        }

        StatRecord record = statRecordOpt.get();
        return convertToResponse(record);
    }

    private StatQueryResponse convertToResponse(StatRecord record) {
        return new StatQueryResponse(
                record.getStatAnswerCount(),
                record.getStatReviewedCount(),
                record.getStatCompletionRate(),
                record.getStatQuestionStat()
        );
    }

    public Map<String, Map<String, Integer>> parseQuestionStatistics(String statJson) {
        try {
            return objectMapper.readValue(statJson, new TypeReference<Map<String, Map<String, Integer>>>() {});
        } catch (JsonProcessingException e) {
            log.error("解析统计数据失败", e);
            return new HashMap<>();
        }
    }
}
