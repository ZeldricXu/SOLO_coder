package com.survey.repository;

import com.survey.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, String> {

    Optional<ReviewRecord> findByReviewId(String reviewId);

    Optional<ReviewRecord> findByAnswerId(String answerId);

    List<ReviewRecord> findByReviewStatus(String reviewStatus);
}
