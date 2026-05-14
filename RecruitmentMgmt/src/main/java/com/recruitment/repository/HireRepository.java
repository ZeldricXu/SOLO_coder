package com.recruitment.repository;

import com.recruitment.common.enums.HireStatus;
import com.recruitment.model.Hire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HireRepository extends JpaRepository<Hire, String> {
    Optional<Hire> findByHireId(String hireId);
    Optional<Hire> findByResumeId(String resumeId);
    List<Hire> findByCandidateId(String candidateId);
    List<Hire> findByHireStatus(HireStatus status);
    boolean existsByHireId(String hireId);
    boolean existsByResumeId(String resumeId);
}
