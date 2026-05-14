package com.survey.repository;

import com.survey.entity.AnswerRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnswerRecordRepository extends JpaRepository<AnswerRecord, String> {

    Optional<AnswerRecord> findByAnswerId(String answerId);

    List<AnswerRecord> findBySurveyId(String surveyId);

    List<AnswerRecord> findBySurveyIdAndAnswerStatus(String surveyId, String answerStatus);

    long countBySurveyId(String surveyId);

    long countBySurveyIdAndAnswerStatus(String surveyId, String answerStatus);

    List<AnswerRecord> findBySurveyIdAndUserId(String surveyId, String userId);

    List<AnswerRecord> findBySurveyIdAndAnswerStatusIn(String surveyId, List<String> statuses);

    boolean existsBySurveyIdAndUserId(String surveyId, String userId);
}
