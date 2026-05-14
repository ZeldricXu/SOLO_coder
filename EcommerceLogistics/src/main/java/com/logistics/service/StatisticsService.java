package com.logistics.service;

import com.logistics.entity.Statistics;
import com.logistics.repository.StatisticsRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;

    private Statistics getOrCreateCurrentStatistics() {
        String currentMonth = IdGenerator.getCurrentMonth();
        Optional<Statistics> existing = statisticsRepository.findByStatMonth(currentMonth);

        if (existing.isPresent()) {
            return existing.get();
        }

        Statistics statistics = new Statistics();
        statistics.setStatId(IdGenerator.generateStatId());
        statistics.setStatMonth(currentMonth);
        return statisticsRepository.save(statistics);
    }

    @Transactional
    public void incrementLogisticsCount() {
        Statistics stats = getOrCreateCurrentStatistics();
        stats.setLogisticsCount(stats.getLogisticsCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementDeliveryCount() {
        Statistics stats = getOrCreateCurrentStatistics();
        stats.setDeliveryCount(stats.getDeliveryCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementDeliveringCount() {
        Statistics stats = getOrCreateCurrentStatistics();
        stats.setDeliveringCount(stats.getDeliveringCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void decrementDeliveringCount() {
        Statistics stats = getOrCreateCurrentStatistics();
        if (stats.getDeliveringCount() > 0) {
            stats.setDeliveringCount(stats.getDeliveringCount() - 1);
            statisticsRepository.save(stats);
        }
    }

    @Transactional
    public void addTotalFee(Double fee) {
        if (fee != null) {
            Statistics stats = getOrCreateCurrentStatistics();
            stats.setTotalFee(stats.getTotalFee() + fee);
            statisticsRepository.save(stats);
        }
    }

    @Transactional
    public void updateAvgDeliveryTime(Double avgTime) {
        Statistics stats = getOrCreateCurrentStatistics();
        stats.setAvgDeliveryTime(avgTime);
        statisticsRepository.save(stats);
    }

    public Statistics getCurrentStatistics() {
        return getOrCreateCurrentStatistics();
    }

    public List<Statistics> getAllStatistics() {
        return statisticsRepository.findAll();
    }
}
