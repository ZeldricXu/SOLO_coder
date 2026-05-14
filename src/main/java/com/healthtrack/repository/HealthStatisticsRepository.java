package com.healthtrack.repository;

import com.healthtrack.entity.HealthStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HealthStatisticsRepository extends JpaRepository<HealthStatistics, String> {
    
    List<HealthStatistics> findByUserId(String userId);
    
    Optional<HealthStatistics> findByUserIdAndStatDate(String userId, LocalDate statDate);
    
    List<HealthStatistics> findByUserIdAndStatDateBetween(String userId, LocalDate start, LocalDate end);
    
    List<HealthStatistics> findByUserIdOrderByStatDateDesc(String userId);
}
