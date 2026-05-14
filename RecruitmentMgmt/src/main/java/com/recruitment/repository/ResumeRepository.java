package com.recruitment.repository;

import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.model.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, String> {

    Optional<Resume> findByResumeId(String resumeId);

    List<Resume> findAll();

    List<Resume> findByPositionId(String positionId);

    List<Resume> findByCandidateId(String candidateId);

    List<Resume> findByResumeStatus(ResumeStatus status);

    List<Resume> findByPositionIdAndResumeStatus(String positionId, ResumeStatus status);

    boolean existsByResumeId(String resumeId);

    boolean existsByPositionIdAndCandidateId(String positionId, String candidateId);

    List<Resume> findByPositionIdAndCandidateIdList(String positionId, String candidateId);

    long countByPositionId(String positionId);

    long countByPositionIdAndResumeStatus(String positionId, ResumeStatus status);

    long countByCandidateId(String candidateId);

    List<Resume> findByPositionIdInAndCandidateId(List<String> positionIds, String candidateId);
}
