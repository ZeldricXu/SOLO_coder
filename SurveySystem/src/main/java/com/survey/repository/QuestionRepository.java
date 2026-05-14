package com.survey.repository;

import com.survey.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuestionRepository extends JpaRepository<Question, String> {

    Optional<Question> findByQuestionId(String questionId);

    List<Question> findBySurvey_SurveyIdOrderByQuestionOrderAsc(String surveyId);

    List<Question> findBySurvey_SurveyId(String surveyId);
}
