package com.mobilestore.repository;

import com.mobilestore.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, String> {

    Optional<Feedback> findByFeedbackId(String feedbackId);

    List<Feedback> findByAppIdOrderByCreatedAtDesc(String appId);

    List<Feedback> findByStatusOrderByCreatedAtDesc(String status);

    List<Feedback> findByAppIdAndStatusOrderByCreatedAtDesc(String appId, String status);

    List<Feedback> findByPriorityOrderByCreatedAtDesc(String priority);

    List<Feedback> findByFeedbackTypeOrderByCreatedAtDesc(String feedbackType);

    List<Feedback> findByAppIdOrderByPriorityAscCreatedAtDesc(String appId);

    List<Feedback> findByAppIdAndStatusOrderByPriorityAscCreatedAtDesc(String appId, String status);

    List<Feedback> findByAppIdAndStatusAndPriorityOrderByPriorityAscCreatedAtDesc(String appId, String status, String priority);

    List<Feedback> findByStatusOrderByPriorityAscCreatedAtDesc(String status);

    List<Feedback> findAllByOrderByPriorityAscCreatedAtDesc();

    long countByAppId(String appId);

    long countByAppIdAndStatus(String appId, String status);

    long countByAppIdAndPriority(String appId, String priority);

    long countByAppIdAndFeedbackType(String appId, String feedbackType);

    long countByStatus(String status);

    long countByPriority(String priority);

    long countByFeedbackType(String feedbackType);

    @Query("SELECT f.status, COUNT(f) FROM Feedback f WHERE f.appId = :appId GROUP BY f.status")
    List<Object[]> countByStatusGrouped(@Param("appId") String appId);

    @Query("SELECT f.priority, COUNT(f) FROM Feedback f WHERE f.appId = :appId GROUP BY f.priority")
    List<Object[]> countByPriorityGrouped(@Param("appId") String appId);

    @Query("SELECT f.feedbackType, COUNT(f) FROM Feedback f WHERE f.appId = :appId GROUP BY f.feedbackType")
    List<Object[]> countByTypeGrouped(@Param("appId") String appId);
}
