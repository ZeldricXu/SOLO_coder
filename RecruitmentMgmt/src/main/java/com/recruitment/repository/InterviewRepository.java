package com.recruitment.repository;

import com.recruitment.common.enums.InterviewStatus;
import com.recruitment.model.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewRepository extends JpaRepository<Interview, String> {
    Optional<Interview> findByInterviewId(String interviewId);
    List<Interview> findByResumeId(String resumeId);
    List<Interview> findByInterviewerId(String interviewerId);
    List<Interview> findByInterviewStatus(InterviewStatus status);
    List<Interview> findByResumeIdAndInterviewStatus(String resumeId, InterviewStatus status);
    Optional<Interview> findFirstByResumeIdOrderByCreatedAtDesc(String resumeId);
    boolean existsByInterviewId(String interviewId);
}
