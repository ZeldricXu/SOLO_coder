package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.entity.HealthReport;
import com.healthtrack.entity.HealthStatistics;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import com.healthtrack.repository.HealthReportRepository;
import com.healthtrack.repository.HealthStatisticsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReportGenerationService {

    @Autowired
    private HealthReportRepository healthReportRepository;

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    @Autowired
    private HealthStatisticsRepository healthStatisticsRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public HealthReport generateDailyReport(String userId, LocalDate date) {
        String reportPeriod = date.toString();
        
        Optional<HealthReport> existing = healthReportRepository
                .findByUserIdAndReportTypeAndReportPeriod(userId, "daily", reportPeriod);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        HealthReport report = new HealthReport();
        report.setReportId("report_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        report.setUserId(userId);
        report.setReportType("daily");
        report.setReportPeriod(reportPeriod);
        report.setReportData(generateDailyReportData(userId, date));
        
        return healthReportRepository.save(report);
    }

    public HealthReport generateMonthlyReport(String userId, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        String reportPeriod = yearMonth.toString();
        
        Optional<HealthReport> existing = healthReportRepository
                .findByUserIdAndReportTypeAndReportPeriod(userId, "monthly", reportPeriod);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        HealthReport report = new HealthReport();
        report.setReportId("report_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        report.setUserId(userId);
        report.setReportType("monthly");
        report.setReportPeriod(reportPeriod);
        report.setReportData(generateMonthlyReportData(userId, yearMonth));
        
        return healthReportRepository.save(report);
    }

    private String generateDailyReportData(String userId, LocalDate date) {
        ObjectNode reportData = objectMapper.createObjectNode();
        
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        
        List<HealthData> dayData = healthDataRepository.findByUserIdAndCollectedAtBetween(userId, startOfDay, endOfDay);
        reportData.put("totalRecords", dayData.size());
        
        long goodQuality = dayData.stream().filter(d -> "good".equals(d.getQuality())).count();
        reportData.put("goodQualityCount", goodQuality);
        reportData.put("abnormalQualityCount", dayData.size() - goodQuality);
        
        List<HealthIndicator> indicators = healthIndicatorRepository.findByUserId(userId);
        ObjectNode indicatorsNode = objectMapper.createObjectNode();
        for (HealthIndicator indicator : indicators) {
            ObjectNode indicatorNode = objectMapper.createObjectNode();
            indicatorNode.put("currentValue", indicator.getCurrentValue());
            indicatorNode.put("averageValue", indicator.getAverageValue());
            indicatorNode.put("trend", indicator.getTrend());
            indicatorNode.put("status", indicator.getStatus());
            indicatorsNode.set(indicator.getIndicatorType(), indicatorNode);
        }
        reportData.set("indicators", indicatorsNode);
        
        Optional<HealthStatistics> stats = healthStatisticsRepository.findByUserIdAndStatDate(userId, date);
        if (stats.isPresent()) {
            HealthStatistics s = stats.get();
            reportData.put("goalProgress", s.getGoalProgress());
            reportData.put("avgHeartRate", s.getAvgHeartRate());
            reportData.put("avgWeight", s.getAvgWeight());
        }
        
        try {
            return objectMapper.writeValueAsString(reportData);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String generateMonthlyReportData(String userId, YearMonth yearMonth) {
        ObjectNode reportData = objectMapper.createObjectNode();
        
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        
        List<HealthData> monthData = healthDataRepository.findByUserIdAndCollectedAtBetween(userId, start, end);
        reportData.put("totalRecords", monthData.size());
        
        long goodQuality = monthData.stream().filter(d -> "good".equals(d.getQuality())).count();
        reportData.put("goodQualityCount", goodQuality);
        reportData.put("abnormalQualityCount", monthData.size() - goodQuality);
        
        ObjectNode dataSummary = objectMapper.createObjectNode();
        monthData.stream()
                .map(HealthData::getDataType)
                .distinct()
                .forEach(type -> {
                    long count = monthData.stream().filter(d -> type.equals(d.getDataType())).count();
                    dataSummary.put(type, count);
                });
        reportData.set("dataTypeSummary", dataSummary);
        
        List<HealthIndicator> indicators = healthIndicatorRepository.findByUserId(userId);
        ObjectNode indicatorsNode = objectMapper.createObjectNode();
        for (HealthIndicator indicator : indicators) {
            ObjectNode indicatorNode = objectMapper.createObjectNode();
            indicatorNode.put("currentValue", indicator.getCurrentValue());
            indicatorNode.put("averageValue", indicator.getAverageValue());
            indicatorNode.put("maxValue", indicator.getMaxValue());
            indicatorNode.put("minValue", indicator.getMinValue());
            indicatorNode.put("trend", indicator.getTrend());
            indicatorNode.put("status", indicator.getStatus());
            indicatorsNode.set(indicator.getIndicatorType(), indicatorNode);
        }
        reportData.set("indicators", indicatorsNode);
        
        List<HealthStatistics> monthStats = healthStatisticsRepository
                .findByUserIdAndStatDateBetween(userId, startDate, endDate);
        
        if (!monthStats.isEmpty()) {
            double avgProgress = monthStats.stream()
                    .filter(s -> s.getGoalProgress() != null)
                    .mapToInt(HealthStatistics::getGoalProgress)
                    .average()
                    .orElse(0);
            reportData.put("averageGoalProgress", Math.round(avgProgress));
            
            double avgHr = monthStats.stream()
                    .filter(s -> s.getAvgHeartRate() != null)
                    .mapToDouble(HealthStatistics::getAvgHeartRate)
                    .average()
                    .orElse(0);
            reportData.put("avgHeartRate", Math.round(avgHr * 100.0) / 100.0);
            
            double avgWt = monthStats.stream()
                    .filter(s -> s.getAvgWeight() != null)
                    .mapToDouble(HealthStatistics::getAvgWeight)
                    .average()
                    .orElse(0);
            reportData.put("avgWeight", Math.round(avgWt * 100.0) / 100.0);
        }
        
        try {
            return objectMapper.writeValueAsString(reportData);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public List<HealthReport> getUserReports(String userId) {
        return healthReportRepository.findByUserIdOrderByGeneratedAtDesc(userId);
    }

    public Optional<HealthReport> getReportById(String reportId) {
        return healthReportRepository.findById(reportId);
    }

    public void deleteReport(String reportId) {
        healthReportRepository.deleteById(reportId);
    }
}
