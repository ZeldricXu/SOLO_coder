package com.adplatform.service;

import com.adplatform.entity.AdHistory;
import com.adplatform.entity.AdReport;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdReportRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ReportService {
    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);
    
    private final AdReportRepository adReportRepository;
    private final AdHistoryRepository adHistoryRepository;
    private final StatisticsService statisticsService;
    private final AnalysisService analysisService;
    private final BudgetService budgetService;

    public ReportService(AdReportRepository adReportRepository,
                        AdHistoryRepository adHistoryRepository,
                        StatisticsService statisticsService,
                        AnalysisService analysisService,
                        BudgetService budgetService) {
        this.adReportRepository = adReportRepository;
        this.adHistoryRepository = adHistoryRepository;
        this.statisticsService = statisticsService;
        this.analysisService = analysisService;
        this.budgetService = budgetService;
    }

    @Transactional
    public AdReport generateDailyReport(String adId, LocalDate reportDate) {
        LocalDate startDate = reportDate;
        LocalDate endDate = reportDate;
        
        Map<String, Object> analysisData = analysisService.analyzeAdPerformance(adId, startDate, endDate);
        
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("adId", adId);
        reportData.put("reportDate", reportDate);
        reportData.put("performance", analysisData);
        
        budgetService.getBudgetByAdId(adId).ifPresent(budget -> {
            Map<String, Object> budgetInfo = new HashMap<>();
            budgetInfo.put("budgetAmount", budget.getBudgetAmount());
            budgetInfo.put("budgetConsumed", budget.getBudgetConsumed());
            budgetInfo.put("budgetRemaining", budget.getBudgetRemaining());
            reportData.put("budget", budgetInfo);
        });
        
        String reportId = IdGenerator.generateId("report");
        AdReport report = AdReport.builder()
                .reportId(reportId)
                .adId(adId)
                .reportType("daily")
                .reportData(reportData)
                .generatedAt(LocalDateTime.now())
                .build();
        
        adReportRepository.save(report);
        logger.info("日报表生成成功: adId={}, reportId={}, date={}", adId, reportId, reportDate);
        
        recordReportHistory(adId, report);
        return report;
    }

    @Transactional
    public AdReport generateWeeklyReport(String adId, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(6);
        
        Map<String, Object> analysisData = analysisService.analyzeAdPerformance(adId, startDate, endDate);
        
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("adId", adId);
        reportData.put("timeRange", Map.of("start", startDate, "end", endDate));
        reportData.put("performance", analysisData);
        reportData.put("dailyDetails", statisticsService.getEffectDetails(adId, startDate, endDate));
        
        String reportId = IdGenerator.generateId("report");
        AdReport report = AdReport.builder()
                .reportId(reportId)
                .adId(adId)
                .reportType("weekly")
                .reportData(reportData)
                .generatedAt(LocalDateTime.now())
                .build();
        
        adReportRepository.save(report);
        logger.info("周报表生成成功: adId={}, reportId={}", adId, reportId);
        
        recordReportHistory(adId, report);
        return report;
    }

    @Transactional
    public AdReport generateMonthlyReport(String adId, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(29);
        
        Map<String, Object> analysisData = analysisService.analyzeAdPerformance(adId, startDate, endDate);
        
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("adId", adId);
        reportData.put("timeRange", Map.of("start", startDate, "end", endDate));
        reportData.put("performance", analysisData);
        
        String reportId = IdGenerator.generateId("report");
        AdReport report = AdReport.builder()
                .reportId(reportId)
                .adId(adId)
                .reportType("monthly")
                .reportData(reportData)
                .generatedAt(LocalDateTime.now())
                .build();
        
        adReportRepository.save(report);
        logger.info("月报表生成成功: adId={}, reportId={}", adId, reportId);
        
        recordReportHistory(adId, report);
        return report;
    }

    public Optional<AdReport> getReportById(String reportId) {
        return adReportRepository.findByReportId(reportId);
    }

    public List<AdReport> getReportsByAdId(String adId) {
        return adReportRepository.findByAdId(adId);
    }

    public List<AdReport> getReportsByAdIdAndType(String adId, String reportType) {
        return adReportRepository.findByAdIdAndReportType(adId, reportType);
    }

    public Optional<AdReport> getLatestReport(String adId, String reportType) {
        return adReportRepository.findTopByAdIdAndReportTypeOrderByGeneratedAtDesc(adId, reportType);
    }

    @Transactional
    public Map<String, Object> generateSummaryReport(String adId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> analysisData = analysisService.analyzeAdPerformance(adId, startDate, endDate);
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("adId", adId);
        summary.put("timeRange", Map.of("start", startDate, "end", endDate));
        summary.put("summary", analysisData);
        
        budgetService.getBudgetByAdId(adId).ifPresent(budget -> {
            Map<String, Object> budgetSummary = new HashMap<>();
            budgetSummary.put("totalBudget", budget.getBudgetAmount());
            budgetSummary.put("totalConsumed", budget.getBudgetConsumed());
            budgetSummary.put("remainingBudget", budget.getBudgetRemaining());
            budgetSummary.put("budgetUtilizationRate", 
                    budget.getBudgetAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                            ? budget.getBudgetConsumed().divide(budget.getBudgetAmount(), 4, java.math.RoundingMode.HALF_UP)
                            : java.math.BigDecimal.ZERO);
            summary.put("budgetSummary", budgetSummary);
        });
        
        logger.info("汇总报表生成成功: adId={}", adId);
        return summary;
    }

    private void recordReportHistory(String adId, AdReport report) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("reportId", report.getReportId());
        historyData.put("reportType", report.getReportType());
        historyData.put("generatedAt", report.getGeneratedAt());
        
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType("REPORT_GENERATED")
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
