package com.survey.repository;

import com.survey.entity.PublishRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PublishRecordRepository extends JpaRepository<PublishRecord, String> {

    Optional<PublishRecord> findByPublishId(String publishId);

    List<PublishRecord> findBySurveyId(String surveyId);

    List<PublishRecord> findBySurveyIdAndPublishStatus(String surveyId, String publishStatus);

    List<PublishRecord> findByPublishStatus(String publishStatus);

    List<PublishRecord> findByPublishStatusAndConfirmStatus(String publishStatus, String confirmStatus);

    List<PublishRecord> findBySurveyIdAndConfirmStatus(String surveyId, String confirmStatus);
}
