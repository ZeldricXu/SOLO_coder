package com.survey.repository;

import com.survey.entity.AnswerData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerDataRepository extends JpaRepository<AnswerData, Long> {

    List<AnswerData> findByAnswerRecord_AnswerId(String answerId);

    List<AnswerData> findByQuestionId(String questionId);
}
