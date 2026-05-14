package com.survey.repository;

import com.survey.entity.Survey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurveyRepository extends JpaRepository<Survey, String> {

    Optional<Survey> findBySurveyId(String surveyId);

    List<Survey> findBySurveyStatus(String surveyStatus);

    List<Survey> findBySurveyType(String surveyType);

    List<Survey> findBySurveyStatusIn(List<String> statuses);
}
