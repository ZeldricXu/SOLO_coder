package com.survey.repository;

import com.survey.entity.AnswerReminderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnswerReminderRecordRepository extends JpaRepository<AnswerReminderRecord, String> {

    Optional<AnswerReminderRecord> findByReminderId(String reminderId);

    List<AnswerReminderRecord> findBySurveyId(String surveyId);

    List<AnswerReminderRecord> findByPublishId(String publishId);

    List<AnswerReminderRecord> findByReminderStatus(String reminderStatus);

    Optional<AnswerReminderRecord> findByPublishIdAndUserId(String publishId, String userId);

    Optional<AnswerReminderRecord> findByPublishIdAndUserEmail(String publishId, String userEmail);

    List<AnswerReminderRecord> findBySurveyIdAndReminderStatus(String surveyId, String reminderStatus);

    List<AnswerReminderRecord> findByPublishIdAndReminderStatus(String publishId, String reminderStatus);

    boolean existsByPublishIdAndUserId(String publishId, String userId);

    boolean existsByPublishIdAndUserEmail(String publishId, String userEmail);
}
