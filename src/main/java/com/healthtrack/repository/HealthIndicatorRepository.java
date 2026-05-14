package com.healthtrack.repository;

import com.healthtrack.entity.HealthIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthIndicatorRepository extends JpaRepository<HealthIndicator, String> {
    
    List<HealthIndicator> findByUserId(String userId);
    
    Optional<HealthIndicator> findByUserIdAndIndicatorType(String userId, String indicatorType);
    
    List<HealthIndicator> findByUserIdAndStatus(String userId, String status);
    
    List<HealthIndicator> findByUserIdAndIndicatorTypeIn(String userId, List<String> indicatorTypes);
    
    boolean existsByUserIdAndIndicatorType(String userId, String indicatorType);
}
