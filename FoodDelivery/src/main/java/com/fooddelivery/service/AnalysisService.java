package com.fooddelivery.service;

import com.fooddelivery.entity.Stat;
import com.fooddelivery.repository.StatRepository;
import com.fooddelivery.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisService {

    @Autowired
    private StatRepository statRepository;

    @Autowired
    private HistoryService historyService;

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @Transactional
    public Stat getOrCreateStat(int month) {
        String statMonth = getCurrentMonthString(month);
        Optional<Stat> statOpt = statRepository.findByStatMonth(statMonth);
        if (statOpt.isPresent()) {
            return statOpt.get();
        }
        Stat stat = new Stat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(statMonth);
        return statRepository.save(stat);
    }

    private String getCurrentMonthString(int month) {
        LocalDateTime now = LocalDateTime.now();
        return String.format("%04d-%02d", now.getYear(), month);
    }

    @Transactional
    public void incrementOrderCount(int month) {
        Stat stat = getOrCreateStat(month);
        stat.setOrderCount(stat.getOrderCount() + 1);
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
        historyService.recordHistory("stat", stat.getStatId(), "update", "增加订单计数");
    }

    @Transactional
    public void incrementDeliveryCount(int month) {
        Stat stat = getOrCreateStat(month);
        stat.setDeliveryCount(stat.getDeliveryCount() + 1);
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
        historyService.recordHistory("stat", stat.getStatId(), "update", "增加配送计数");
    }

    @Transactional
    public void incrementCancelCount(int month) {
        Stat stat = getOrCreateStat(month);
        stat.setCancelCount(stat.getCancelCount() + 1);
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
    }

    @Transactional
    public void addTotalAmount(int month, double amount) {
        Stat stat = getOrCreateStat(month);
        stat.setTotalAmount(stat.getTotalAmount() + amount);
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
    }

    @Transactional
    public void addDeliveryTime(int month, long minutes) {
        Stat stat = getOrCreateStat(month);
        stat.setTotalDeliveryTime(stat.getTotalDeliveryTime() + minutes);
        if (stat.getDeliveryCount() > 0) {
            stat.setAvgDeliveryTime((double) stat.getTotalDeliveryTime() / stat.getDeliveryCount());
        }
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
    }

    @Transactional
    public void incrementReviewCount(int month) {
        Stat stat = getOrCreateStat(month);
        stat.setReviewCount(stat.getReviewCount() + 1);
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
    }

    @Transactional
    public void addRating(int month, int rating) {
        Stat stat = getOrCreateStat(month);
        int totalReviews = stat.getReviewCount();
        double currentAvg = stat.getAvgRating() != null ? stat.getAvgRating() : 0.0;
        double totalRating = currentAvg * (totalReviews - 1);
        if (totalReviews > 0) {
            double newAvg = (totalRating + rating) / totalReviews;
            stat.setAvgRating(newAvg);
        } else {
            stat.setAvgRating((double) rating);
        }
        stat.setUpdatedAt(LocalDateTime.now());
        statRepository.save(stat);
    }

    public Optional<Stat> getStatByMonth(String month) {
        return statRepository.findByStatMonth(month);
    }

    public List<Stat> getAllStats() {
        return statRepository.findAllByOrderByStatMonthDesc();
    }

    public Optional<Stat> getCurrentMonthStat() {
        String currentMonth = LocalDateTime.now().format(MONTH_FORMATTER);
        return statRepository.findByStatMonth(currentMonth);
    }
}
