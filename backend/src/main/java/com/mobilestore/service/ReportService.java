package com.mobilestore.service;

import com.mobilestore.entity.Statistics;
import com.mobilestore.repository.StatisticsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class ReportService {

    @Autowired
    private StatisticsRepository statisticsRepository;

    public Map<String, Object> generateDailyReport(String appId, LocalDate date) {
        Statistics stats = statisticsRepository.findByAppIdAndStatDate(appId, date)
                .orElse(null);

        Map<String, Object> report = new HashMap<>();
        report.put("app_id", appId);
        report.put("report_date", date);
        report.put("report_type", "daily");

        if (stats != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("download_count", stats.getDownloadCount());
            data.put("active_users", stats.getActiveUsers());
            data.put("avg_rating", stats.getAvgRating());
            data.put("feedback_count", stats.getFeedbackCount());
            report.put("data", data);
        }

        report.put("generated_at", new Date());
        return report;
    }

    public Map<String, Object> generateWeeklyReport(String appId, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(6);
        List<Statistics> stats = statisticsRepository.findByAppIdAndStatDateBetweenOrderByStatDateAsc(
                appId, startDate, endDate);

        Map<String, Object> report = new HashMap<>();
        report.put("app_id", appId);
        report.put("start_date", startDate);
        report.put("end_date", endDate);
        report.put("report_type", "weekly");

        long totalDownloads = 0;
        long totalActiveUsers = 0;
        long totalFeedbacks = 0;
        double totalRating = 0;
        int ratingCount = 0;

        List<Map<String, Object>> dailyBreakdown = new ArrayList<>();

        for (Statistics stat : stats) {
            totalDownloads += stat.getDownloadCount();
            totalActiveUsers += stat.getActiveUsers();
            totalFeedbacks += stat.getFeedbackCount();
            if (stat.getAvgRating() != null) {
                totalRating += stat.getAvgRating();
                ratingCount++;
            }

            Map<String, Object> daily = new HashMap<>();
            daily.put("date", stat.getStatDate());
            daily.put("downloads", stat.getDownloadCount());
            daily.put("active_users", stat.getActiveUsers());
            daily.put("rating", stat.getAvgRating());
            dailyBreakdown.add(daily);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_downloads", totalDownloads);
        summary.put("total_active_users", totalActiveUsers);
        summary.put("avg_rating", ratingCount > 0 ? Math.round(totalRating / ratingCount * 100.0) / 100.0 : 0.0);
        summary.put("total_feedbacks", totalFeedbacks);

        report.put("summary", summary);
        report.put("daily_breakdown", dailyBreakdown);
        report.put("generated_at", new Date());

        return report;
    }

    public Map<String, Object> generateMonthlyReport(String appId, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(29);
        List<Statistics> stats = statisticsRepository.findByAppIdAndStatDateBetweenOrderByStatDateAsc(
                appId, startDate, endDate);

        Map<String, Object> report = new HashMap<>();
        report.put("app_id", appId);
        report.put("start_date", startDate);
        report.put("end_date", endDate);
        report.put("report_type", "monthly");

        long totalDownloads = 0;
        long totalActiveUsers = 0;
        long totalFeedbacks = 0;
        double totalRating = 0;
        int ratingCount = 0;

        long maxDownloads = 0;
        long minDownloads = Long.MAX_VALUE;
        long maxActiveUsers = 0;
        long minActiveUsers = Long.MAX_VALUE;

        for (Statistics stat : stats) {
            totalDownloads += stat.getDownloadCount();
            totalActiveUsers += stat.getActiveUsers();
            totalFeedbacks += stat.getFeedbackCount();
            if (stat.getAvgRating() != null) {
                totalRating += stat.getAvgRating();
                ratingCount++;
            }

            maxDownloads = Math.max(maxDownloads, stat.getDownloadCount());
            minDownloads = Math.min(minDownloads, stat.getDownloadCount());
            maxActiveUsers = Math.max(maxActiveUsers, stat.getActiveUsers());
            minActiveUsers = Math.min(minActiveUsers, stat.getActiveUsers());
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_downloads", totalDownloads);
        summary.put("total_active_users", totalActiveUsers);
        summary.put("avg_rating", ratingCount > 0 ? Math.round(totalRating / ratingCount * 100.0) / 100.0 : 0.0);
        summary.put("total_feedbacks", totalFeedbacks);
        summary.put("avg_daily_downloads", stats.size() > 0 ? totalDownloads / stats.size() : 0);
        summary.put("avg_daily_active_users", stats.size() > 0 ? totalActiveUsers / stats.size() : 0);
        summary.put("max_daily_downloads", maxDownloads);
        summary.put("min_daily_downloads", minDownloads == Long.MAX_VALUE ? 0 : minDownloads);
        summary.put("max_daily_active_users", maxActiveUsers);
        summary.put("min_daily_active_users", minActiveUsers == Long.MAX_VALUE ? 0 : minActiveUsers);

        report.put("summary", summary);
        report.put("days_analyzed", stats.size());
        report.put("generated_at", new Date());

        return report;
    }

    public Map<String, Object> generateCustomReport(String appId, LocalDate startDate, LocalDate endDate) {
        List<Statistics> stats = statisticsRepository.findByAppIdAndStatDateBetweenOrderByStatDateAsc(
                appId, startDate, endDate);

        Map<String, Object> report = new HashMap<>();
        report.put("app_id", appId);
        report.put("start_date", startDate);
        report.put("end_date", endDate);
        report.put("report_type", "custom");

        List<Map<String, Object>> dataList = new ArrayList<>();
        for (Statistics stat : stats) {
            Map<String, Object> data = new HashMap<>();
            data.put("date", stat.getStatDate());
            data.put("downloads", stat.getDownloadCount());
            data.put("active_users", stat.getActiveUsers());
            data.put("rating", stat.getAvgRating());
            data.put("feedbacks", stat.getFeedbackCount());
            dataList.add(data);
        }

        report.put("data", dataList);
        report.put("record_count", dataList.size());
        report.put("generated_at", new Date());

        return report;
    }
}
