package com.parking.service;

import com.parking.dto.PaymentRequest;
import com.parking.dto.PaymentResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.ParkingLot;
import com.parking.entity.PreCalculationConfig;
import com.parking.entity.SettlementRecord;
import com.parking.exception.ParkingException;
import com.parking.repository.PreCalculationConfigRepository;
import com.parking.repository.SettlementRecordRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SettlementService {

    @Autowired
    private SettlementRecordRepository settlementRecordRepository;

    @Autowired
    private PreCalculationConfigRepository preCalculationConfigRepository;

    @Autowired
    private ParkingLotService parkingLotService;

    @Autowired
    private EntryService entryService;

    @Autowired
    private StatisticsService statisticsService;

    private final Map<String, PreCalculationResult> preCalculatedFees = new ConcurrentHashMap<>();
    private final List<PreCalculationConfig> preCalculationCache = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static class PreCalculationResult {
        String entryId;
        double estimatedFee;
        LocalDateTime calculationTime;
        LocalDateTime estimatedExitTime;
        int durationMinutes;
        String durationCategory;

        public PreCalculationResult(String entryId, double estimatedFee, LocalDateTime calculationTime,
                                     LocalDateTime estimatedExitTime, int durationMinutes, String durationCategory) {
            this.entryId = entryId;
            this.estimatedFee = estimatedFee;
            this.calculationTime = calculationTime;
            this.estimatedExitTime = estimatedExitTime;
            this.durationMinutes = durationMinutes;
            this.durationCategory = durationCategory;
        }

        public String getEntryId() { return entryId; }
        public double getEstimatedFee() { return estimatedFee; }
        public LocalDateTime getCalculationTime() { return calculationTime; }
        public LocalDateTime getEstimatedExitTime() { return estimatedExitTime; }
        public int getDurationMinutes() { return durationMinutes; }
        public String getDurationCategory() { return durationCategory; }
    }

    private static final int DEFAULT_SHORT_THRESHOLD_MINUTES = 60;
    private static final int DEFAULT_LONG_PARKING_EARLY_HOURS = 1;
    private static final int DEFAULT_SHORT_PARKING_LATE_HOURS = 1;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final Map<String, AtomicInteger> retryAttempts = new ConcurrentHashMap<>();

    public PreCalculationConfig getPreCalculationConfigByDuration(long durationMinutes) {
        refreshCacheIfNeeded();

        PreCalculationConfig bestMatch = null;
        for (PreCalculationConfig config : preCalculationCache) {
            if (config.getEnabled() && durationMinutes >= config.getDurationThresholdMinutes()) {
                if (bestMatch == null || config.getDurationThresholdMinutes() > bestMatch.getDurationThresholdMinutes()) {
                    bestMatch = config;
                }
            }
        }

        return bestMatch;
    }

    private void refreshCacheIfNeeded() {
        if (preCalculationCache.isEmpty()) {
            refreshPreCalculationCache();
        }
    }

    public void refreshPreCalculationCache() {
        preCalculationCache.clear();
        List<PreCalculationConfig> configs = preCalculationConfigRepository.findAllEnabledOrderByThreshold();
        preCalculationCache.addAll(configs);
    }

    public int getPreCalculationTiming(EntryRecord entryRecord) {
        LocalDateTime entryTime = entryRecord.getEntryTime();
        LocalDateTime now = LocalDateTime.now();
        long durationMinutes = Duration.between(entryTime, now).toMinutes();

        PreCalculationConfig config = getPreCalculationConfigByDuration(durationMinutes);
        if (config != null) {
            return config.getDurationThresholdMinutes() - config.getPreCalculateBeforeExitMinutes();
        }

        return -1;
    }

    public boolean shouldPreCalculateNow(EntryRecord entryRecord) {
        LocalDateTime entryTime = entryRecord.getEntryTime();
        LocalDateTime now = LocalDateTime.now();
        long minutesSinceEntry = Duration.between(entryTime, now).toMinutes();

        PreCalculationConfig config = getPreCalculationConfigByDuration(minutesSinceEntry);
        if (config == null) {
            return minutesSinceEntry >= DEFAULT_SHORT_THRESHOLD_MINUTES + DEFAULT_LONG_PARKING_EARLY_HOURS * 60;
        }

        int calculationThreshold = config.getDurationThresholdMinutes() - config.getPreCalculateBeforeExitMinutes();
        return minutesSinceEntry >= calculationThreshold;
    }

    public String getDurationCategory(long minutesSinceEntry) {
        PreCalculationConfig config = getPreCalculationConfigByDuration(minutesSinceEntry);
        if (config != null) {
            return config.getDurationCategory();
        }

        if (minutesSinceEntry >= DEFAULT_SHORT_THRESHOLD_MINUTES) {
            return "long";
        }
        return "short";
    }

    public int getEstimatedPreCalculationMinutes(String durationCategory) {
        refreshCacheIfNeeded();

        for (PreCalculationConfig config : preCalculationCache) {
            if (config.getEnabled() && config.getDurationCategory().equalsIgnoreCase(durationCategory)) {
                return config.getDurationThresholdMinutes() - config.getPreCalculateBeforeExitMinutes();
            }
        }

        if ("long".equalsIgnoreCase(durationCategory)) {
            return DEFAULT_SHORT_THRESHOLD_MINUTES + DEFAULT_LONG_PARKING_EARLY_HOURS * 60;
        }
        return DEFAULT_SHORT_THRESHOLD_MINUTES + DEFAULT_SHORT_PARKING_LATE_HOURS * 60;
    }

    public PreCalculationResult preCalculateFee(String entryId) {
        EntryRecord entryRecord = entryService.getEntryById(entryId);
        ParkingLot parkingLot = parkingLotService.getParkingLotById(entryRecord.getParkingId());

        LocalDateTime entryTime = entryRecord.getEntryTime();
        LocalDateTime now = LocalDateTime.now();
        long minutesSoFar = Duration.between(entryTime, now).toMinutes();
        if (minutesSoFar <= 0) minutesSoFar = 1;

        String category = getDurationCategory(minutesSoFar);
        int estimateMinutes = getEstimatedPreCalculationMinutes(category);

        LocalDateTime estimatedExitTime = now.plusMinutes(Math.max(60, estimateMinutes));
        long estimatedTotalMinutes = Duration.between(entryTime, estimatedExitTime).toMinutes();
        if (estimatedTotalMinutes <= 0) estimatedTotalMinutes = 1;

        double estimatedFee = calculateParkingFee(parkingLot, entryTime, estimatedExitTime);

        PreCalculationResult result = new PreCalculationResult(
                entryId,
                estimatedFee,
                now,
                estimatedExitTime,
                (int) minutesSoFar,
                category
        );

        preCalculatedFees.put(entryId, result);

        return result;
    }

    public PreCalculationResult getPreCalculatedFee(String entryId) {
        return preCalculatedFees.get(entryId);
    }

    public boolean hasPreCalculatedFee(String entryId) {
        return preCalculatedFees.containsKey(entryId);
    }

    public void clearPreCalculatedFee(String entryId) {
        preCalculatedFees.remove(entryId);
    }

    public List<PreCalculationConfig> getAllPreCalculationConfigs() {
        return preCalculationConfigRepository.findAllEnabledOrderByThreshold();
    }

    @Transactional
    public PreCalculationConfig createOrUpdatePreCalculationConfig(String durationCategory,
                                                                    int durationThresholdMinutes,
                                                                    int preCalculateBeforeExitMinutes,
                                                                    String description) {
        PreCalculationConfig config = preCalculationConfigRepository.findByDurationCategory(durationCategory)
                .orElse(new PreCalculationConfig());

        config.setDurationCategory(durationCategory);
        config.setDurationThresholdMinutes(durationThresholdMinutes);
        config.setPreCalculateBeforeExitMinutes(preCalculateBeforeExitMinutes);
        config.setDescription(description);
        config.setEnabled(true);

        PreCalculationConfig saved = preCalculationConfigRepository.save(config);
        refreshPreCalculationCache();

        return saved;
    }

    @Transactional
    public SettlementRecord createSettlement(EntryRecord entryRecord, ExitRecord exitRecord) {
        ParkingLot parkingLot = parkingLotService.getParkingLotById(entryRecord.getParkingId());
        
        double parkingFee = calculateParkingFee(parkingLot, entryRecord.getEntryTime(), exitRecord.getExitTime());

        SettlementRecord settlement = new SettlementRecord();
        settlement.setSettlementId(IdGenerator.generateSettlementId());
        settlement.setEntryId(entryRecord.getEntryId());
        settlement.setExitId(exitRecord.getExitId());
        settlement.setVehicleId(entryRecord.getVehicleId());
        settlement.setParkingFee(parkingFee);
        settlement.setPaymentStatus("pending");

        return settlementRecordRepository.save(settlement);
    }

    public double calculateParkingFee(ParkingLot parkingLot, LocalDateTime entryTime, LocalDateTime exitTime) {
        Duration duration = Duration.between(entryTime, exitTime);
        long minutes = duration.toMinutes();
        if (minutes <= 0) {
            minutes = 1;
        }

        if ("fixed".equals(parkingLot.getChargingType()) && parkingLot.getFixedFee() != null) {
            return parkingLot.getFixedFee();
        }

        double hourlyRate = parkingLot.getHourlyRate();
        double hours = Math.ceil(minutes / 60.0);
        return hourlyRate * hours;
    }

    public SettlementRecord getSettlementById(String settlementId) {
        return settlementRecordRepository.findBySettlementId(settlementId)
                .orElseThrow(() -> new ParkingException(404, "结算记录不存在: " + settlementId));
    }

    public List<SettlementRecord> getAllSettlements() {
        return settlementRecordRepository.findAll();
    }

    public List<SettlementRecord> getSettlementsByStatus(String status) {
        return settlementRecordRepository.findByPaymentStatus(status);
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        if (request.getSettlementId() == null || request.getSettlementId().trim().isEmpty()) {
            throw new ParkingException(400, "结算ID不能为空");
        }

        SettlementRecord settlement = getSettlementById(request.getSettlementId());

        if ("paid".equals(settlement.getPaymentStatus())) {
            throw new ParkingException(400, "该订单已支付");
        }

        boolean paymentSuccess = processPaymentGateway(settlement.getParkingFee(), request.getPaymentMethod());

        if (!paymentSuccess) {
            throw new ParkingException(400, "支付失败，请重试");
        }

        settlement.setPaymentMethod(request.getPaymentMethod());
        settlement.setPaymentStatus("paid");
        settlement.setSettlementTime(LocalDateTime.now());

        settlementRecordRepository.save(settlement);

        statisticsService.addTotalAmount(settlement.getParkingFee());

        PaymentResponse response = new PaymentResponse();
        response.setStatus("paid");
        response.setSettlementId(settlement.getSettlementId());
        response.setAmount(settlement.getParkingFee());
        response.setPaymentMethod(request.getPaymentMethod());

        return response;
    }

    private boolean processPaymentGateway(double amount, String paymentMethod) {
        return true;
    }

    @Transactional
    public SettlementRecord retryPayment(String settlementId) {
        SettlementRecord settlement = getSettlementById(settlementId);
        
        if ("paid".equals(settlement.getPaymentStatus())) {
            return settlement;
        }

        AtomicInteger attempts = retryAttempts.computeIfAbsent(settlementId, k -> new AtomicInteger(0));
        
        if (attempts.get() >= MAX_RETRY_ATTEMPTS) {
            throw new ParkingException(400, "已达到最大重试次数(" + MAX_RETRY_ATTEMPTS + ")，请联系客服");
        }

        attempts.incrementAndGet();
        
        boolean paymentSuccess = processPaymentGateway(settlement.getParkingFee(), settlement.getPaymentMethod());

        if (paymentSuccess) {
            settlement.setPaymentStatus("paid");
            settlement.setSettlementTime(LocalDateTime.now());
            settlementRecordRepository.save(settlement);
            statisticsService.addTotalAmount(settlement.getParkingFee());
            retryAttempts.remove(settlementId);
        }

        return settlement;
    }

    public int getRetryCount(String settlementId) {
        AtomicInteger attempts = retryAttempts.get(settlementId);
        return attempts != null ? attempts.get() : 0;
    }

    public int getMaxRetryAttempts() {
        return MAX_RETRY_ATTEMPTS;
    }

    public boolean canRetry(String settlementId) {
        AtomicInteger attempts = retryAttempts.get(settlementId);
        return attempts == null || attempts.get() < MAX_RETRY_ATTEMPTS;
    }

    public void resetRetryCounter(String settlementId) {
        retryAttempts.remove(settlementId);
    }
}
