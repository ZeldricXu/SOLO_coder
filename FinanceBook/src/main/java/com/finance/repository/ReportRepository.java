package com.finance.repository;

import com.finance.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByAccountIdOrderByReportPeriodDesc(String accountId);
    Optional<Report> findByAccountIdAndReportPeriod(String accountId, String reportPeriod);
    List<Report> findByReportPeriod(String reportPeriod);
}
