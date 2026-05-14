package com.healthtrack.repository;

import com.healthtrack.entity.HealthReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthReportRepository extends JpaRepository<HealthReport, String> {
    
    List<HealthReport> findByUserId(String userId);
    
    List<HealthReport> findByUserIdOrderByGeneratedAtDesc(String userId);
    
    List<HealthReport> findByUserIdAndReportType(String userId, String reportType);
    
    Optional<HealthReport> findByUserIdAndReportTypeAndReportPeriod(String userId, String reportType, String reportPeriod);
    
    List<HealthReport> findTop10ByUserIdOrderByGeneratedAtDesc(String userId);
}
