package com.survey.repository;

import com.survey.entity.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, String> {

    Optional<AnalysisReport> findByReportId(String reportId);

    List<AnalysisReport> findBySurveyIdOrderByCreatedAtDesc(String surveyId);

    List<AnalysisReport> findByReportStatus(String reportStatus);
}
