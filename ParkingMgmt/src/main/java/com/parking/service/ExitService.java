package com.parking.service;

import com.parking.dto.ExitRequest;
import com.parking.dto.ExitResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.SettlementRecord;
import com.parking.exception.ParkingException;
import com.parking.repository.ExitRecordRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ExitService {

    @Autowired
    private ExitRecordRepository exitRecordRepository;

    @Autowired
    private EntryService entryService;

    @Autowired
    private ParkingSpaceService parkingSpaceService;

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HistoryService historyService;

    private final Map<String, ExitProcessingResult> processingResults = new ConcurrentHashMap<>();
    private final Map<String, String> asyncSettlementStatus = new ConcurrentHashMap<>();

    public static class ExitProcessingResult {
        private String exitId;
        private String settlementId;
        private double fee;
        private int parkingDuration;
        private String entryTime;
        private String exitTime;
        private String settlementStatus;
        private String errorMessage;

        public ExitProcessingResult(String exitId, String settlementId, double fee, int parkingDuration,
                                    String entryTime, String exitTime, String settlementStatus) {
            this.exitId = exitId;
            this.settlementId = settlementId;
            this.fee = fee;
            this.parkingDuration = parkingDuration;
            this.entryTime = entryTime;
            this.exitTime = exitTime;
            this.settlementStatus = settlementStatus;
        }

        public String getExitId() { return exitId; }
        public String getSettlementId() { return settlementId; }
        public double getFee() { return fee; }
        public int getParkingDuration() { return parkingDuration; }
        public String getEntryTime() { return entryTime; }
        public String getExitTime() { return exitTime; }
        public String getSettlementStatus() { return settlementStatus; }
        public void setSettlementStatus(String status) { this.settlementStatus = status; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public ExitResponse processExitAsync(ExitRequest request) {
        if (request.getEntryId() == null || request.getEntryId().trim().isEmpty()) {
            throw new ParkingException(400, "入场ID不能为空");
        }

        EntryRecord entryRecord = entryService.getEntryById(request.getEntryId());

        if ("exited".equals(entryRecord.getEntryStatus())) {
            throw new ParkingException(400, "车辆已出场，无法重复处理");
        }

        LocalDateTime exitTime = LocalDateTime.now();
        Duration duration = Duration.between(entryRecord.getEntryTime(), exitTime);
        int parkingDuration = (int) Math.max(1, Math.ceil(duration.toMinutes()));

        ExitRecord exitRecord = new ExitRecord();
        String exitId = IdGenerator.generateExitId();
        exitRecord.setExitId(exitId);
        exitRecord.setEntryId(entryRecord.getEntryId());
        exitRecord.setVehicleId(entryRecord.getVehicleId());
        exitRecord.setSpaceId(entryRecord.getSpaceId());
        exitRecord.setExitTime(exitTime);
        exitRecord.setParkingDuration(parkingDuration);
        exitRecord.setExitStatus("pending_settlement");

        entryService.updateEntryStatus(entryRecord.getEntryId(), "exiting");

        parkingSpaceService.updateSpaceStatus(entryRecord.getSpaceId(), "available");

        vehicleService.updateVehicleStatus(entryRecord.getVehicleId(), "idle");

        statisticsService.incrementExitCount();

        ExitProcessingResult result = new ExitProcessingResult(
                exitId,
                null,
                0.0,
                parkingDuration,
                entryRecord.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                exitTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "processing"
        );
        processingResults.put(entryRecord.getEntryId(), result);
        asyncSettlementStatus.put(entryRecord.getEntryId(), "processing");

        executeAsyncSettlement(entryRecord, exitRecord);

        ExitResponse response = new ExitResponse();
        response.setExitId(exitId);
        response.setFee(0.0);
        response.setParkingDuration(parkingDuration);
        response.setSettlementId(null);
        response.setEntryTime(entryRecord.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.setExitTime(exitTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return response;
    }

    @Async
    public void executeAsyncSettlement(EntryRecord entryRecord, ExitRecord exitRecord) {
        try {
            Thread.sleep(100);

            SettlementRecord settlement = settlementService.createSettlement(entryRecord, exitRecord);
            exitRecord.setParkingFee(settlement.getParkingFee());
            exitRecord.setExitStatus("completed");
            exitRecordRepository.save(exitRecord);

            entryService.updateEntryStatus(entryRecord.getEntryId(), "exited");

            settlementService.retryPayment(settlement.getSettlementId());

            ExitProcessingResult result = processingResults.get(entryRecord.getEntryId());
            if (result != null) {
                result.setSettlementId(settlement.getSettlementId());
                result.setFee(settlement.getParkingFee());
                result.setSettlementStatus("completed");
            }
            asyncSettlementStatus.put(entryRecord.getEntryId(), "completed");

            historyService.recordExit(exitRecord, entryRecord);

        } catch (Exception e) {
            ExitProcessingResult result = processingResults.get(entryRecord.getEntryId());
            if (result != null) {
                result.setSettlementStatus("failed");
                result.setErrorMessage(e.getMessage());
            }
            asyncSettlementStatus.put(entryRecord.getEntryId(), "failed");

            retryAsyncSettlement(entryRecord, exitRecord, 1);
        }
    }

    private void retryAsyncSettlement(EntryRecord entryRecord, ExitRecord exitRecord, int attempt) {
        if (attempt > 3) {
            return;
        }

        try {
            Thread.sleep(200 * attempt);

            SettlementRecord settlement = settlementService.createSettlement(entryRecord, exitRecord);
            exitRecord.setParkingFee(settlement.getParkingFee());
            exitRecord.setExitStatus("completed");
            exitRecordRepository.save(exitRecord);

            settlementService.retryPayment(settlement.getSettlementId());

            ExitProcessingResult result = processingResults.get(entryRecord.getEntryId());
            if (result != null) {
                result.setSettlementId(settlement.getSettlementId());
                result.setFee(settlement.getParkingFee());
                result.setSettlementStatus("completed");
            }
            asyncSettlementStatus.put(entryRecord.getEntryId(), "completed");

        } catch (Exception e) {
            retryAsyncSettlement(entryRecord, exitRecord, attempt + 1);
        }
    }

    public ExitProcessingResult getProcessingResult(String entryId) {
        return processingResults.get(entryId);
    }

    public String getAsyncSettlementStatus(String entryId) {
        return asyncSettlementStatus.get(entryId);
    }

    public boolean isSettlementCompleted(String entryId) {
        String status = asyncSettlementStatus.get(entryId);
        return "completed".equals(status);
    }

    @Transactional
    public ExitResponse processExit(ExitRequest request) {
        if (request.getEntryId() == null || request.getEntryId().trim().isEmpty()) {
            throw new ParkingException(400, "入场ID不能为空");
        }

        EntryRecord entryRecord = entryService.getEntryById(request.getEntryId());

        if ("exited".equals(entryRecord.getEntryStatus())) {
            throw new ParkingException(400, "车辆已出场，无法重复处理");
        }

        LocalDateTime exitTime = LocalDateTime.now();
        Duration duration = Duration.between(entryRecord.getEntryTime(), exitTime);
        int parkingDuration = (int) Math.max(1, Math.ceil(duration.toMinutes()));

        ExitRecord exitRecord = new ExitRecord();
        exitRecord.setExitId(IdGenerator.generateExitId());
        exitRecord.setEntryId(entryRecord.getEntryId());
        exitRecord.setVehicleId(entryRecord.getVehicleId());
        exitRecord.setSpaceId(entryRecord.getSpaceId());
        exitRecord.setExitTime(exitTime);
        exitRecord.setParkingDuration(parkingDuration);
        exitRecord.setExitStatus("completed");

        SettlementRecord settlement = settlementService.createSettlement(entryRecord, exitRecord);
        exitRecord.setParkingFee(settlement.getParkingFee());

        exitRecordRepository.save(exitRecord);

        entryService.updateEntryStatus(entryRecord.getEntryId(), "exited");

        parkingSpaceService.updateSpaceStatus(entryRecord.getSpaceId(), "available");

        vehicleService.updateVehicleStatus(entryRecord.getVehicleId(), "idle");

        statisticsService.incrementExitCount();

        historyService.recordExit(exitRecord, entryRecord);

        ExitResponse response = new ExitResponse();
        response.setExitId(exitRecord.getExitId());
        response.setFee(settlement.getParkingFee());
        response.setParkingDuration(parkingDuration);
        response.setSettlementId(settlement.getSettlementId());
        response.setEntryTime(entryRecord.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.setExitTime(exitTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return response;
    }

    public ExitRecord getExitById(String exitId) {
        return exitRecordRepository.findByExitId(exitId)
                .orElseThrow(() -> new ParkingException(404, "出场记录不存在: " + exitId));
    }

    public ExitRecord getExitByEntryId(String entryId) {
        return exitRecordRepository.findByEntryId(entryId)
                .orElseThrow(() -> new ParkingException(404, "该入场记录没有对应的出场记录"));
    }

    public List<ExitRecord> getAllExits() {
        return exitRecordRepository.findAll();
    }

    public List<ExitRecord> getExitsByVehicle(String vehicleId) {
        return exitRecordRepository.findByVehicleId(vehicleId);
    }
}
