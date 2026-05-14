package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.dto.AnswerSubmitRequest;
import com.survey.dto.AnswerSubmitResponse;
import com.survey.entity.AnswerData;
import com.survey.entity.AnswerRecord;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.AnswerDataRepository;
import com.survey.repository.AnswerRecordRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

    private final AnswerRecordRepository answerRecordRepository;
    private final AnswerDataRepository answerDataRepository;
    private final SurveyService surveyService;
    private final ReviewService reviewService;
    private final StatisticsService statisticsService;
    private final AsyncStatisticsService asyncStatisticsService;
    private final HistoryService historyService;
    private final AnswerReminderService answerReminderService;

    @Transactional
    public AnswerSubmitResponse submitAnswer(AnswerSubmitRequest request) {
        log.info("提交答卷，问卷ID: {}", request.getSurveyId());

        Survey survey = surveyService.findSurvey(request.getSurveyId())
                .orElseThrow(() -> SurveyException.surveyNotFound(request.getSurveyId()));

        if (!surveyService.isSurveyActive(request.getSurveyId())) {
            if (SurveyConstants.SURVEY_STATUS_CLOSED.equals(survey.getSurveyStatus())) {
                throw SurveyException.surveyClosed(request.getSurveyId());
            }
            if (survey.getSurveyDeadline() != null && LocalDateTime.now().isAfter(survey.getSurveyDeadline())) {
                throw SurveyException.surveyExpired(request.getSurveyId());
            }
            throw new SurveyException(400, "问卷不可用: " + survey.getSurveyStatus());
        }

        List<Question> questions = surveyService.getSurveyQuestions(request.getSurveyId());
        validateAnswers(request, questions);

        AnswerRecord record = new AnswerRecord();
        record.setAnswerId(IdGenerator.generateAnswerId());
        record.setSurveyId(request.getSurveyId());
        record.setUserId(request.getUserId());
        record.setAnswerStatus(SurveyConstants.ANSWER_STATUS_SUBMITTED);
        record.setAnswerTime(LocalDateTime.now());

        AnswerRecord savedRecord = answerRecordRepository.save(record);

        List<AnswerData> answerDataList = createAnswerData(savedRecord, request.getAnswerData());
        savedRecord.setAnswerData(answerDataList);

        if (Boolean.TRUE.equals(survey.getNeedReview())) {
            reviewService.createReviewRequest(savedRecord.getAnswerId());
            savedRecord.setAnswerStatus(SurveyConstants.ANSWER_STATUS_REVIEWING);
            answerRecordRepository.save(savedRecord);
        } else {
            asyncStatisticsService.triggerStatUpdate(request.getSurveyId());
        }

        historyService.recordAnswerHistory(savedRecord.getAnswerId(), "SUBMIT_ANSWER",
                "提交答卷，问卷: " + survey.getSurveyName(), request.getUserId());
        historyService.recordSurveyHistory(request.getSurveyId(), "ANSWER_SUBMITTED",
                "收到答卷，答卷ID: " + savedRecord.getAnswerId(), null);

        if (request.getUserId() != null) {
            answerReminderService.markUserRemindersCompleted(request.getSurveyId(), request.getUserId(), null);
        }

        log.info("答卷提交成功: {}", savedRecord.getAnswerId());
        return new AnswerSubmitResponse(savedRecord.getAnswerId(), savedRecord.getAnswerStatus());
    }

    private void validateAnswers(AnswerSubmitRequest request, List<Question> questions) {
        Map<String, Question> questionMap = questions.stream()
                .collect(Collectors.toMap(Question::getQuestionId, q -> q));

        Map<String, String> answerMap = request.getAnswerData().stream()
                .collect(Collectors.toMap(
                        AnswerSubmitRequest.AnswerDataItem::getQuestionId,
                        AnswerSubmitRequest.AnswerDataItem::getAnswerValue,
                        (existing, replacement) -> existing
                ));

        for (Question question : questions) {
            if (Boolean.TRUE.equals(question.getRequired())) {
                String answer = answerMap.get(question.getQuestionId());
                if (answer == null || answer.trim().isEmpty()) {
                    throw SurveyException.answerIncomplete("必填题目未作答: " + question.getQuestionId());
                }
            }

            String answer = answerMap.get(question.getQuestionId());
            if (answer != null && !answer.isEmpty()) {
                validateAnswerType(question, answer);
            }
        }
    }

    private void validateAnswerType(Question question, String answer) {
        String type = question.getQuestionType();
        switch (type) {
            case SurveyConstants.QUESTION_TYPE_SINGLE:
                if (question.getOptions() != null && !question.getOptions().isEmpty()) {
                    if (!question.getOptions().contains(answer)) {
                        throw SurveyException.answerTypeError(question.getQuestionId());
                    }
                }
                break;
            case SurveyConstants.QUESTION_TYPE_RATING:
                try {
                    int rating = Integer.parseInt(answer);
                    if (rating < 1 || rating > 5) {
                        throw SurveyException.answerTypeError(question.getQuestionId());
                    }
                } catch (NumberFormatException e) {
                    throw SurveyException.answerTypeError(question.getQuestionId());
                }
                break;
        }
    }

    private List<AnswerData> createAnswerData(AnswerRecord record, List<AnswerSubmitRequest.AnswerDataItem> items) {
        List<AnswerData> answerDataList = new ArrayList<>();
        for (AnswerSubmitRequest.AnswerDataItem item : items) {
            AnswerData data = new AnswerData();
            data.setAnswerRecord(record);
            data.setQuestionId(item.getQuestionId());
            data.setAnswerValue(item.getAnswerValue());
            answerDataList.add(data);
        }
        return answerDataRepository.saveAll(answerDataList);
    }

    public AnswerRecord getAnswer(String answerId) {
        return answerRecordRepository.findByAnswerId(answerId)
                .orElseThrow(() -> SurveyException.answerNotFound(answerId));
    }

    public List<AnswerRecord> getAnswersBySurvey(String surveyId) {
        return answerRecordRepository.findBySurveyId(surveyId);
    }

    public List<AnswerRecord> getAnswersBySurveyAndStatus(String surveyId, String status) {
        return answerRecordRepository.findBySurveyIdAndAnswerStatus(surveyId, status);
    }

    public long getAnswerCount(String surveyId) {
        return answerRecordRepository.countBySurveyId(surveyId);
    }

    public long getAnswerCountByStatus(String surveyId, String status) {
        return answerRecordRepository.countBySurveyIdAndAnswerStatus(surveyId, status);
    }

    public List<AnswerData> getAnswerDetails(String answerId) {
        return answerDataRepository.findByAnswerRecord_AnswerId(answerId);
    }

    @Transactional
    public void updateAnswerStatus(String answerId, String status) {
        AnswerRecord record = answerRecordRepository.findByAnswerId(answerId)
                .orElseThrow(() -> SurveyException.answerNotFound(answerId));
        record.setAnswerStatus(status);
        answerRecordRepository.save(record);
    }

    @Transactional
    public void setReviewId(String answerId, String reviewId) {
        AnswerRecord record = answerRecordRepository.findByAnswerId(answerId)
                .orElseThrow(() -> SurveyException.answerNotFound(answerId));
        record.setReviewId(reviewId);
        answerRecordRepository.save(record);
    }
}
