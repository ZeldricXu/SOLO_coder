package com.healthtrack.repository;

import com.healthtrack.entity.HealthHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HealthHistoryRepository extends JpaRepository<HealthHistory, Long> {
    
    List<HealthHistory> findByUserId(String userId);
    
    List<HealthHistory> findByUserIdOrderByRecordedAtDesc(String userId);
    
    List<HealthHistory> findByUserIdAndDataType(String userId, String dataType);
    
    List<HealthHistory> findByUserIdAndRecordedAtBetween(String userId, LocalDateTime start, LocalDateTime end);
    
    List<HealthHistory> findTop50ByUserIdOrderByRecordedAtDesc(String userId);
}
