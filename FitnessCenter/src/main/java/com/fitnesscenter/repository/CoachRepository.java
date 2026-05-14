package com.fitnesscenter.repository;

import com.fitnesscenter.model.Coach;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoachRepository extends JpaRepository<Coach, String> {
    
    Optional<Coach> findByCoachId(String coachId);
    
    List<Coach> findByCoachStatus(String coachStatus);
    
    List<Coach> findByCoachType(String coachType);
    
    List<Coach> findByGymId(String gymId);
    
    List<Coach> findByCoachStatusAndCoachType(String coachStatus, String coachType);
    
    boolean existsByCoachId(String coachId);
}
