package com.cms.repository;

import com.cms.entity.PublishWarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PublishWarningRepository extends JpaRepository<PublishWarning, String> {

    List<PublishWarning> findByContentId(String contentId);

    List<PublishWarning> findByPublishId(String publishId);

    List<PublishWarning> findByPublisherId(String publisherId);

    List<PublishWarning> findByWarningStatus(String warningStatus);

    List<PublishWarning> findByImportanceLevel(String importanceLevel);

    List<PublishWarning> findByPublisherIdAndWarningStatus(String publisherId, String warningStatus);

    @Query("SELECT w FROM PublishWarning w WHERE w.warningStatus = 'pending' AND w.warningTime <= :now ORDER BY w.warningTime ASC")
    List<PublishWarning> findPendingWarningsToSend(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(w) FROM PublishWarning w WHERE w.publisherId = :publisherId AND w.warningStatus = 'pending'")
    long countPendingWarningsByPublisherId(@Param("publisherId") String publisherId);

    @Query("SELECT w FROM PublishWarning w WHERE w.scheduledPublishTime BETWEEN :start AND :end ORDER BY w.scheduledPublishTime ASC")
    List<PublishWarning> findByScheduledPublishTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    void deleteByContentId(String contentId);
}
