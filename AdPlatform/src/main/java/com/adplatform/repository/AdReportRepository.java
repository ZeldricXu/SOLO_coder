package com.adplatform.repository;

import com.adplatform.entity.AdReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdReportRepository extends JpaRepository<AdReport, String> {
    Optional<AdReport> findByReportId(String reportId);
    List<AdReport> findByAdId(String adId);
    List<AdReport> findByAdIdAndReportType(String adId, String reportType);
    List<AdReport> findByAdIdAndGeneratedAtBetween(String adId, LocalDateTime startTime, LocalDateTime endTime);
    Optional<AdReport> findTopByAdIdAndReportTypeOrderByGeneratedAtDesc(String adId, String reportType);
}
