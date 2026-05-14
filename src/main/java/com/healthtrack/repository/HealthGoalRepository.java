package com.healthtrack.repository;

import com.healthtrack.entity.HealthGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthGoalRepository extends JpaRepository<HealthGoal, String> {
    
    List<HealthGoal> findByUserId(String userId);
    
    List<HealthGoal> findByUserIdAndStatus(String userId, String status);
    
    Optional<HealthGoal> findByUserIdAndGoalType(String userId, String goalType);
    
    List<HealthGoal> findByUserIdAndGoalTypeIn(String userId, List<String> goalTypes);
    
    List<HealthGoal> findByStatus(String status);
}
