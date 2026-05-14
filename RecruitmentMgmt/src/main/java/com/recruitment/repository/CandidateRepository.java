package com.recruitment.repository;

import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.model.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, String> {
    Optional<Candidate> findByCandidateId(String candidateId);
    Optional<Candidate> findByCandidatePhone(String candidatePhone);
    List<Candidate> findByCandidateStatus(CandidateStatus status);
    List<Candidate> findByCandidateNameContaining(String name);
    boolean existsByCandidateId(String candidateId);
    boolean existsByCandidatePhone(String candidatePhone);
}
