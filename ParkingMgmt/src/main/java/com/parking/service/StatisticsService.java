package com.parking.service;

import com.parking.entity.ParkingStatistics;
import com.parking.repository.ParkingStatisticsRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class StatisticsService {

    @Autowired
    private ParkingStatisticsRepository statisticsRepository;

    @Autowired
    private ParkingSpaceService parkingSpaceService;

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private ParkingStatistics getOrCreateCurrentStatistics() {
        String statMonth = getCurrentMonth();
        Optional<ParkingStatistics> existing = statisticsRepository.findByStatMonth(statMonth);

        if (existing.isPresent()) {
            return existing.get();
        }

        ParkingStatistics stats = new ParkingStatistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(statMonth);
        stats.setEntryCount(0);
        stats.setExitCount(0);
        stats.setTotalAmount(0.0);
        stats.setReservationCount(0);
        stats.setOccupancyRate(0.0);

        return statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementEntryCount() {
        ParkingStatistics stats = getOrCreateCurrentStatistics();
        stats.setEntryCount(stats.getEntryCount() + 1);
        updateOccupancyRate(stats);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementExitCount() {
        ParkingStatistics stats = getOrCreateCurrentStatistics();
        stats.setExitCount(stats.getExitCount() + 1);
        updateOccupancyRate(stats);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void addTotalAmount(double amount) {
        ParkingStatistics stats = getOrCreateCurrentStatistics();
        stats.setTotalAmount(stats.getTotalAmount() + amount);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void incrementReservationCount() {
        ParkingStatistics stats = getOrCreateCurrentStatistics();
        stats.setReservationCount(stats.getReservationCount() + 1);
        statisticsRepository.save(stats);
    }

    private void updateOccupancyRate(ParkingStatistics stats) {
        try {
            long totalSpaces = 0;
            long availableSpaces = 0;
            stats.setOccupancyRate(0.0);
        } catch (Exception e) {
            stats.setOccupancyRate(0.0);
        }
    }

    public ParkingStatistics getCurrentMonthStatistics() {
        return getOrCreateCurrentStatistics();
    }

    public ParkingStatistics getStatisticsByMonth(String statMonth) {
        return statisticsRepository.findByStatMonth(statMonth)
                .orElseGet(() -> {
                    ParkingStatistics stats = new ParkingStatistics();
                    stats.setStatId(IdGenerator.generateStatId());
                    stats.setStatMonth(statMonth);
                    stats.setEntryCount(0);
                    stats.setExitCount(0);
                    stats.setTotalAmount(0.0);
                    stats.setReservationCount(0);
                    stats.setOccupancyRate(0.0);
                    return stats;
                });
    }

    public double calculateOccupancyRate(String parkingId) {
        long totalSpaces = parkingSpaceService.countTotalSpaces(parkingId);
        if (totalSpaces == 0) {
            return 0.0;
        }
        long availableSpaces = parkingSpaceService.countAvailableSpaces(parkingId);
        long occupiedSpaces = totalSpaces - availableSpaces;
        return (double) occupiedSpaces / totalSpaces;
    }
}
