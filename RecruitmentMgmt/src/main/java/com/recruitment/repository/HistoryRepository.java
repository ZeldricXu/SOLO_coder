package com.recruitment.repository;

import com.recruitment.common.enums.HistoryType;
import com.recruitment.model.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HistoryRepository extends JpaRepository<History, String> {
    Optional<History> findByHistoryId(String historyId);
    List<History> findByHistoryType(HistoryType historyType);
    List<History> findByRelatedId(String relatedId);
    List<History> findByPositionId(String positionId);
    List<History> findByResumeId(String resumeId);
    List<History> findByCandidateId(String candidateId);
    List<History> findByInterviewId(String interviewId);
    List<History> findByHireId(String hireId);
    List<History> findByPositionIdOrderByCreatedAtDesc(String positionId);
    List<History> findByResumeIdOrderByCreatedAtDesc(String resumeId);
    List<History> findByCandidateIdOrderByCreatedAtDesc(String candidateId);
    boolean existsByHistoryId(String historyId);
}
