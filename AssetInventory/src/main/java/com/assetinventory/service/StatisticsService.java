package com.assetinventory.service;

import com.assetinventory.entity.InventoryStatistics;
import com.assetinventory.repository.InventoryStatisticsRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class StatisticsService {

    private final InventoryStatisticsRepository statisticsRepository;

    @Autowired
    public StatisticsService(InventoryStatisticsRepository statisticsRepository) {
        this.statisticsRepository = statisticsRepository;
    }

    public InventoryStatistics getOrCreateCurrentMonthStatistics() {
        String currentMonth = IdGenerator.getCurrentMonth();
        Optional<InventoryStatistics> existing = statisticsRepository.findByStatMonth(currentMonth);

        if (existing.isPresent()) {
            return existing.get();
        }

        InventoryStatistics stats = new InventoryStatistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(currentMonth);
        stats.setTaskCount(0);
        stats.setCountCount(0);
        stats.setDiffCount(0);
        stats.setProcessedDiffCount(0);
        stats.setAccuracyRate(1.0);

        return statisticsRepository.save(stats);
    }

    public InventoryStatistics incrementTaskCount() {
        InventoryStatistics stats = getOrCreateCurrentMonthStatistics();
        stats.setTaskCount(stats.getTaskCount() + 1);
        return statisticsRepository.save(stats);
    }

    public InventoryStatistics incrementCountCount() {
        InventoryStatistics stats = getOrCreateCurrentMonthStatistics();
        stats.setCountCount(stats.getCountCount() + 1);
        updateAccuracyRate(stats);
        return statisticsRepository.save(stats);
    }

    public InventoryStatistics incrementDiffCount() {
        InventoryStatistics stats = getOrCreateCurrentMonthStatistics();
        stats.setDiffCount(stats.getDiffCount() + 1);
        updateAccuracyRate(stats);
        return statisticsRepository.save(stats);
    }

    public InventoryStatistics incrementProcessedDiffCount() {
        InventoryStatistics stats = getOrCreateCurrentMonthStatistics();
        stats.setProcessedDiffCount(stats.getProcessedDiffCount() + 1);
        return statisticsRepository.save(stats);
    }

    private void updateAccuracyRate(InventoryStatistics stats) {
        if (stats.getCountCount() > 0) {
            int normalCount = stats.getCountCount() - stats.getDiffCount();
            double accuracy = (double) normalCount / stats.getCountCount();
            stats.setAccuracyRate(Math.max(0.0, Math.min(1.0, accuracy)));
        }
    }

    public InventoryStatistics getStatisticsByMonth(String month) {
        return statisticsRepository.findByStatMonth(month)
                .orElse(null);
    }

    public InventoryStatistics getCurrentMonthStatistics() {
        return getOrCreateCurrentMonthStatistics();
    }
}
