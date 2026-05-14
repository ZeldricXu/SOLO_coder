package com.example.mailservice.service;

import com.example.mailservice.model.MailStatistics;
import com.example.mailservice.repository.MailStatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final MailStatisticsRepository statisticsRepository;

    @Transactional
    public void incrementSentCount() {
        MailStatistics stats = getOrCreateTodayStatistics();
        stats.setSentCount(stats.getSentCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementReceivedCount() {
        MailStatistics stats = getOrCreateTodayStatistics();
        stats.setReceivedCount(stats.getReceivedCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementFailedCount() {
        MailStatistics stats = getOrCreateTodayStatistics();
        stats.setFailedCount(stats.getFailedCount() + 1);
        statisticsRepository.save(stats);
    }

    private MailStatistics getOrCreateTodayStatistics() {
        LocalDate today = LocalDate.now();
        Optional<MailStatistics> existing = statisticsRepository.findByStatDate(today);

        if (existing.isPresent()) {
            return existing.get();
        }

        MailStatistics stats = MailStatistics.builder()
                .statId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .statDate(today)
                .sentCount(0)
                .receivedCount(0)
                .failedCount(0)
                .avgResponseTime(0)
                .build();

        return statisticsRepository.save(stats);
    }

    public MailStatistics getTodayStatistics() {
        return getOrCreateTodayStatistics();
    }

    public DailyReport getDailyReport(LocalDate date) {
        Optional<MailStatistics> statsOpt = statisticsRepository.findByStatDate(date);
        MailStatistics stats = statsOpt.orElseGet(() -> MailStatistics.builder()
                .statDate(date)
                .sentCount(0)
                .receivedCount(0)
                .failedCount(0)
                .avgResponseTime(0)
                .build());

        return DailyReport.builder()
                .date(date)
                .sentCount(stats.getSentCount())
                .receivedCount(stats.getReceivedCount())
                .failedCount(stats.getFailedCount())
                .avgResponseTime(stats.getAvgResponseTime())
                .successRate(calculateSuccessRate(stats.getSentCount(), stats.getFailedCount()))
                .build();
    }

    public RangeReport getRangeReport(LocalDate startDate, LocalDate endDate) {
        Long totalSent = statisticsRepository.sumSentCountBetween(startDate, endDate);
        Long totalReceived = statisticsRepository.sumReceivedCountBetween(startDate, endDate);
        Long totalFailed = statisticsRepository.sumFailedCountBetween(startDate, endDate);

        long sent = totalSent != null ? totalSent : 0L;
        long received = totalReceived != null ? totalReceived : 0L;
        long failed = totalFailed != null ? totalFailed : 0L;

        return RangeReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalSent(sent)
                .totalReceived(received)
                .totalFailed(failed)
                .successRate(calculateSuccessRate((int) sent, (int) failed))
                .totalMail(sent + received)
                .build();
    }

    private double calculateSuccessRate(int sent, int failed) {
        if (sent + failed == 0) {
            return 100.0;
        }
        return (double) sent / (sent + failed) * 100;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DailyReport {
        private LocalDate date;
        private int sentCount;
        private int receivedCount;
        private int failedCount;
        private int avgResponseTime;
        private double successRate;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RangeReport {
        private LocalDate startDate;
        private LocalDate endDate;
        private long totalSent;
        private long totalReceived;
        private long totalFailed;
        private double successRate;
        private long totalMail;
    }
}
