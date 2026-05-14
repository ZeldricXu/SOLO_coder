package com.recruitment.analysis;

import com.recruitment.common.util.IdGenerator;
import com.recruitment.model.Statistics;
import com.recruitment.repository.StatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    private final StatisticsRepository statisticsRepository;

    @Transactional
    public void incrementPositionCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setPositionCount(stats.getPositionCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 职位数量统计 +1, 月份: {}", month);
    }

    @Transactional
    public void incrementResumeCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setResumeCount(stats.getResumeCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 简历数量统计 +1, 月份: {}", month);
    }

    @Transactional
    public void incrementScreenedCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setScreenedCount(stats.getScreenedCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 筛选数量统计 +1, 月份: {}", month);
    }

    @Transactional
    public void incrementInterviewCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setInterviewCount(stats.getInterviewCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 面试数量统计 +1, 月份: {}", month);
    }

    @Transactional
    public void incrementHireCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setHireCount(stats.getHireCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 录用数量统计 +1, 月份: {}", month);
    }

    @Transactional
    public void incrementRejectCount() {
        String month = getCurrentMonth();
        Statistics stats = getOrCreateStatistics(month);
        stats.setRejectCount(stats.getRejectCount() + 1);
        statisticsRepository.save(stats);
        log.debug("Analysis: 淘汰数量统计 +1, 月份: {}", month);
    }

    public Statistics getCurrentMonthStatistics() {
        String month = getCurrentMonth();
        return getOrCreateStatistics(month);
    }

    public Statistics getStatisticsByMonth(String month) {
        return getOrCreateStatistics(month);
    }

    private Statistics getOrCreateStatistics(String month) {
        Optional<Statistics> existing = statisticsRepository.findByStatMonth(month);
        if (existing.isPresent()) {
            return existing.get();
        }
        Statistics newStats = Statistics.builder()
                .statId(IdGenerator.generateStatId())
                .statMonth(month)
                .positionCount(0)
                .resumeCount(0)
                .screenedCount(0)
                .interviewCount(0)
                .hireCount(0)
                .rejectCount(0)
                .build();
        return statisticsRepository.save(newStats);
    }

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
