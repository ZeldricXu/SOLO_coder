package com.recruitment.repository;

import com.recruitment.common.enums.InterviewerStatus;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.model.Interviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewerRepository extends JpaRepository<Interviewer, String> {
    Optional<Interviewer> findByInterviewerId(String interviewerId);
    List<Interviewer> findByInterviewerStatus(InterviewerStatus status);
    List<Interviewer> findByInterviewerType(InterviewType type);
    List<Interviewer> findByInterviewerDepartment(String department);
    List<Interviewer> findByInterviewerStatusAndInterviewerType(InterviewerStatus status, InterviewType type);
    boolean existsByInterviewerId(String interviewerId);
}
