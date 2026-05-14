package com.fooddelivery.service;

import com.fooddelivery.entity.Rider;
import com.fooddelivery.repository.RiderRepository;
import com.fooddelivery.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RiderService {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Rider createRider(Rider rider) {
        rider.setRiderId(IdGenerator.generateRiderId());
        Rider saved = riderRepository.save(rider);
        historyService.recordHistory("rider", saved.getRiderId(), "create", "创建骑手：" + saved.getRiderName());
        return saved;
    }

    public Optional<Rider> getRiderById(String riderId) {
        return riderRepository.findByRiderId(riderId);
    }

    public List<Rider> getAllRiders() {
        return riderRepository.findAll();
    }

    public List<Rider> getRidersByRegion(String region) {
        return riderRepository.findByRiderRegion(region);
    }

    public List<Rider> getAvailableRiders(String region) {
        return riderRepository.findByRiderRegionAndRiderStatus(region, "available");
    }

    public List<Rider> getRidersByStatus(String status) {
        return riderRepository.findByRiderStatus(status);
    }

    @Transactional
    public Rider updateRider(String riderId, Rider rider) {
        Rider existing = riderRepository.findByRiderId(riderId)
                .orElseThrow(() -> new RuntimeException("骑手不存在"));
        existing.setRiderName(rider.getRiderName() != null ? rider.getRiderName() : existing.getRiderName());
        existing.setRiderPhone(rider.getRiderPhone() != null ? rider.getRiderPhone() : existing.getRiderPhone());
        existing.setRiderRegion(rider.getRiderRegion() != null ? rider.getRiderRegion() : existing.getRiderRegion());
        existing.setRiderStatus(rider.getRiderStatus() != null ? rider.getRiderStatus() : existing.getRiderStatus());
        Rider saved = riderRepository.save(existing);
        historyService.recordHistory("rider", saved.getRiderId(), "update", "更新骑手信息");
        return saved;
    }

    @Transactional
    public Rider updateRiderStatus(String riderId, String status) {
        Rider rider = riderRepository.findByRiderId(riderId)
                .orElseThrow(() -> new RuntimeException("骑手不存在"));
        rider.setRiderStatus(status);
        Rider saved = riderRepository.save(rider);
        historyService.recordHistory("rider", saved.getRiderId(), "status_change", "更新骑手状态为：" + status);
        return saved;
    }

    @Transactional
    public Rider updateRiderStatusWithOrder(String riderId, String status, String orderId) {
        Rider rider = riderRepository.findByRiderId(riderId)
                .orElseThrow(() -> new RuntimeException("骑手不存在"));
        rider.setRiderStatus(status);
        if ("available".equals(status)) {
            rider.setRiderCurrentOrder(null);
        } else {
            rider.setRiderCurrentOrder(orderId);
        }
        Rider saved = riderRepository.save(rider);
        historyService.recordHistory("rider", saved.getRiderId(), "status_change", "更新骑手状态为：" + status + ", 订单：" + orderId);
        return saved;
    }

    @Transactional
    public Rider incrementDeliveryCount(String riderId) {
        Rider rider = riderRepository.findByRiderId(riderId)
                .orElseThrow(() -> new RuntimeException("骑手不存在"));
        int current = rider.getRiderCount() != null ? rider.getRiderCount() : 0;
        rider.setRiderCount(current + 1);
        Rider saved = riderRepository.save(rider);
        historyService.recordHistory("rider", saved.getRiderId(), "delivery_count", "增加配送计数，当前：" + (current + 1));
        return saved;
    }

    @Transactional
    public Rider updateRiderRating(String riderId, Integer newRating) {
        Rider rider = riderRepository.findByRiderId(riderId)
                .orElseThrow(() -> new RuntimeException("骑手不存在"));
        int currentCount = rider.getRiderRatingCount() != null ? rider.getRiderRatingCount() : 0;
        double currentRating = rider.getRiderRating() != null ? rider.getRiderRating() : 0.0;
        double totalRating = currentRating * currentCount;
        int newCount = currentCount + 1;
        double newAvgRating = (totalRating + newRating) / newCount;
        rider.setRiderRating(newAvgRating);
        rider.setRiderRatingCount(newCount);
        Rider saved = riderRepository.save(rider);
        historyService.recordHistory("rider", saved.getRiderId(), "rating_update", "更新骑手评分，新评分：" + newAvgRating);
        return saved;
    }

    public Optional<Rider> selectBestRider(String region) {
        List<Rider> availableRiders = getAvailableRiders(region);
        if (availableRiders.isEmpty()) {
            return Optional.empty();
        }
        Rider bestRider = availableRiders.get(0);
        double bestScore = calculateRiderScore(bestRider);
        for (Rider rider : availableRiders) {
            double score = calculateRiderScore(rider);
            if (score > bestScore) {
                bestScore = score;
                bestRider = rider;
            }
        }
        return Optional.of(bestRider);
    }

    private double calculateRiderScore(Rider rider) {
        double ratingScore = rider.getRiderRating() != null ? rider.getRiderRating() * 10 : 0;
        int countScore = rider.getRiderCount() != null ? Math.min(rider.getRiderCount(), 100) : 0;
        return ratingScore + countScore * 0.1;
    }
}
