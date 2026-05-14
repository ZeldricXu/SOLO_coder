package com.cms.repository;

import com.cms.entity.ReviewReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReviewReminderRepository extends JpaRepository<ReviewReminder, String> {

    List<ReviewReminder> findByContentId(String contentId);

    List<ReviewReminder> findByReviewerId(String reviewerId);

    List<ReviewReminder> findByReviewerIdAndReminderStatus(String reviewerId, String reminderStatus);

    List<ReviewReminder> findByContentIdAndReminderStatus(String contentId, String reminderStatus);

    List<ReviewReminder> findByReminderStatus(String reminderStatus);

    List<ReviewReminder> findByUrgencyLevel(String urgencyLevel);

    List<ReviewReminder> findByReminderStatusAndNextReminderTimeLessThanEqual(String reminderStatus, LocalDateTime nextReminderTime);

    @Query("SELECT r FROM ReviewReminder r WHERE r.reminderStatus = :status AND r.nextReminderTime <= :now ORDER BY r.nextReminderTime ASC")
    List<ReviewReminder> findPendingRemindersToSend(@Param("status") String status, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(r) FROM ReviewReminder r WHERE r.reviewerId = :reviewerId AND r.reminderStatus = 'unread'")
    long countUnreadRemindersByReviewerId(@Param("reviewerId") String reviewerId);

    @Query("SELECT r FROM ReviewReminder r WHERE r.contentId = :contentId ORDER BY r.reminderTime DESC")
    List<ReviewReminder> findLatestByContentId(@Param("contentId") String contentId);

    void deleteByContentId(String contentId);
}
