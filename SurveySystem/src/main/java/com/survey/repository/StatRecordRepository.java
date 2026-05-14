package com.survey.repository;

import com.survey.entity.StatRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatRecordRepository extends JpaRepository<StatRecord, String> {

    Optional<StatRecord> findByStatId(String statId);

    Optional<StatRecord> findBySurveyId(String surveyId);
}
