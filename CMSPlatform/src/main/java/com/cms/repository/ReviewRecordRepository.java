package com.cms.repository;

import com.cms.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, String> {

    List<ReviewRecord> findByContentId(String contentId);

    List<ReviewRecord> findByReviewerId(String reviewerId);

    List<ReviewRecord> findByReviewStatus(String reviewStatus);

    @Query("SELECT COUNT(r) FROM ReviewRecord r WHERE r.reviewStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(r) FROM ReviewRecord r WHERE r.contentId = :contentId")
    long countByContentId(@Param("contentId") String contentId);
}
