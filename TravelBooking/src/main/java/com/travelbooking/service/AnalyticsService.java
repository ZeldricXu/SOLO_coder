package com.travelbooking.service;

import com.travelbooking.model.TravelStat;
import com.travelbooking.repository.TravelStatRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final TravelStatRepository travelStatRepository;

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private TravelStat getOrCreateStat() {
        String month = getCurrentMonth();
        Optional<TravelStat> existing = travelStatRepository.findByStatMonth(month);

        if (existing.isPresent()) {
            return existing.get();
        }

        TravelStat stat = new TravelStat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(month);
        stat.setRouteCount(0);
        stat.setBookingCount(0);
        stat.setTouristCount(0);
        stat.setTotalAmount(BigDecimal.ZERO);
        stat.setDepartedCount(0);
        stat.setCompletedCount(0);

        return travelStatRepository.save(stat);
    }

    @Transactional
    public void updateBookingStatistics(BigDecimal amount, int touristCount) {
        TravelStat stat = getOrCreateStat();
        stat.setBookingCount(stat.getBookingCount() + 1);
        stat.setTouristCount(stat.getTouristCount() + touristCount);
        stat.setTotalAmount(stat.getTotalAmount().add(amount));
        travelStatRepository.save(stat);
    }

    @Transactional
    public void updateDepartedStatistics() {
        TravelStat stat = getOrCreateStat();
        stat.setDepartedCount(stat.getDepartedCount() + 1);
        travelStatRepository.save(stat);
    }

    @Transactional
    public void updateCompletedStatistics() {
        TravelStat stat = getOrCreateStat();
        stat.setCompletedCount(stat.getCompletedCount() + 1);
        travelStatRepository.save(stat);
    }

    @Transactional
    public void updateSettlementStatistics(BigDecimal amount) {
        TravelStat stat = getOrCreateStat();
        stat.setTotalAmount(stat.getTotalAmount().add(amount));
        travelStatRepository.save(stat);
    }

    @Transactional
    public void incrementRouteCount() {
        TravelStat stat = getOrCreateStat();
        stat.setRouteCount(stat.getRouteCount() + 1);
        travelStatRepository.save(stat);
    }

    @Transactional
    public void updateRouteStatistics(com.travelbooking.model.Route route) {
        TravelStat stat = getOrCreateStat();
        travelStatRepository.save(stat);
    }

    @Transactional
    public void updateSettlementStatistics() {
        TravelStat stat = getOrCreateStat();
        travelStatRepository.save(stat);
    }

    public List<TravelStat> getAllStats() {
        return travelStatRepository.findAll();
    }

    public Optional<TravelStat> getStatByMonth(String month) {
        return travelStatRepository.findByStatMonth(month);
    }

    public Optional<TravelStat> getCurrentMonthStats() {
        return travelStatRepository.findByStatMonth(getCurrentMonth());
    }
}
