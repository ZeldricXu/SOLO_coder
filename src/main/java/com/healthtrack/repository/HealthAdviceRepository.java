package com.healthtrack.repository;

import com.healthtrack.entity.HealthAdvice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HealthAdviceRepository extends JpaRepository<HealthAdvice, String> {
    
    List<HealthAdvice> findByUserId(String userId);
    
    List<HealthAdvice> findByUserIdAndReadStatus(String userId, String readStatus);
    
    List<HealthAdvice> findByUserIdAndPushedFalse(String userId);
    
    List<HealthAdvice> findByUserIdAndGeneratedAtAfter(String userId, LocalDateTime since);
    
    List<HealthAdvice> findByUserIdOrderByPriorityAscGeneratedAtDesc(String userId);
    
    List<HealthAdvice> findByUserIdAndPriority(String userId, String priority);
}
