package com.parking.service;

import com.parking.dto.EntryRequest;
import com.parking.dto.EntryResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.entity.Vehicle;
import com.parking.exception.ParkingException;
import com.parking.repository.EntryRecordRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class EntryService {

    @Autowired
    private EntryRecordRepository entryRecordRepository;

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private ParkingSpaceService parkingSpaceService;

    @Autowired
    private ParkingLotService parkingLotService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public EntryResponse processEntry(EntryRequest request) {
        if (request.getVehicleNumber() == null || request.getVehicleNumber().trim().isEmpty()) {
            throw new ParkingException(400, "车牌号码不能为空");
        }

        if (request.getParkingId() == null || request.getParkingId().trim().isEmpty()) {
            throw new ParkingException(400, "停车场ID不能为空");
        }

        ParkingLot parkingLot = parkingLotService.getParkingLotById(request.getParkingId());

        String effectiveVehicleType = request.getVehicleType() != null 
                ? request.getVehicleType().toLowerCase().trim() 
                : "standard";

        Vehicle vehicle = vehicleService.createOrGetVehicle(
                request.getVehicleNumber(),
                effectiveVehicleType,
                request.getVehicleOwner(),
                request.getVehiclePhone()
        );

        List<EntryRecord> existingEntries = entryRecordRepository.findByVehicleIdAndEntryStatus(
                vehicle.getVehicleId(), "parked");
        if (!existingEntries.isEmpty()) {
            throw new ParkingException(400, "该车辆已在场内，无法重复入场");
        }

        ParkingSpace allocatedSpace = parkingSpaceService.allocateSpaceByType(
                request.getParkingId(), 
                null, 
                effectiveVehicleType);

        EntryRecord entryRecord = new EntryRecord();
        entryRecord.setEntryId(IdGenerator.generateEntryId());
        entryRecord.setVehicleId(vehicle.getVehicleId());
        entryRecord.setSpaceId(allocatedSpace.getSpaceId());
        entryRecord.setParkingId(parkingLot.getParkingId());
        entryRecord.setVehicleNumber(vehicle.getVehicleNumber());
        entryRecord.setSpaceNumber(allocatedSpace.getSpaceNumber());
        entryRecord.setEntryTime(LocalDateTime.now());
        entryRecord.setEntryStatus("parked");
        entryRecord.setVehicleType(effectiveVehicleType);

        entryRecordRepository.save(entryRecord);

        vehicleService.updateVehicleStatus(vehicle.getVehicleId(), "parked");

        statisticsService.incrementEntryCount();

        historyService.recordEntry(entryRecord);

        EntryResponse response = new EntryResponse();
        response.setEntryId(entryRecord.getEntryId());
        response.setSpaceNumber(allocatedSpace.getSpaceNumber());
        response.setSpaceId(allocatedSpace.getSpaceId());
        response.setVehicleNumber(vehicle.getVehicleNumber());
        response.setEntryTime(entryRecord.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.setVehicleType(effectiveVehicleType);

        return response;
    }

    @Transactional
    public EntryResponse processEntryWithLock(EntryRequest request) {
        if (request.getVehicleNumber() == null || request.getVehicleNumber().trim().isEmpty()) {
            throw new ParkingException(400, "车牌号码不能为空");
        }

        if (request.getParkingId() == null || request.getParkingId().trim().isEmpty()) {
            throw new ParkingException(400, "停车场ID不能为空");
        }

        ParkingLot parkingLot = parkingLotService.getParkingLotById(request.getParkingId());

        String effectiveVehicleType = request.getVehicleType() != null 
                ? request.getVehicleType().toLowerCase().trim() 
                : "standard";

        Vehicle vehicle = vehicleService.createOrGetVehicle(
                request.getVehicleNumber(),
                effectiveVehicleType,
                request.getVehicleOwner(),
                request.getVehiclePhone()
        );

        List<EntryRecord> existingEntries = entryRecordRepository.findByVehicleIdAndEntryStatus(
                vehicle.getVehicleId(), "parked");
        if (!existingEntries.isEmpty()) {
            throw new ParkingException(400, "该车辆已在场内，无法重复入场");
        }

        List<ParkingSpace> availableSpaces = parkingSpaceService.getAvailableSpaces(request.getParkingId());
        if (availableSpaces.isEmpty()) {
            throw new ParkingException(400, "停车场暂无可用车位");
        }

        ParkingSpace lockedSpace = null;
        for (ParkingSpace space : availableSpaces) {
            if (parkingSpaceService.isVehicleTypeAllowedForSpace(effectiveVehicleType, space.getSpaceType())) {
                if (parkingSpaceService.tryLockSpace(space.getSpaceId(), effectiveVehicleType)) {
                    lockedSpace = space;
                    break;
                }
            }
        }

        if (lockedSpace == null) {
            throw new ParkingException(400, "暂时无法分配车位，请稍后重试");
        }

        try {
            ParkingSpace occupiedSpace = parkingSpaceService.confirmLockAndOccupy(
                    lockedSpace.getSpaceId(), 
                    effectiveVehicleType);

            EntryRecord entryRecord = new EntryRecord();
            entryRecord.setEntryId(IdGenerator.generateEntryId());
            entryRecord.setVehicleId(vehicle.getVehicleId());
            entryRecord.setSpaceId(occupiedSpace.getSpaceId());
            entryRecord.setParkingId(parkingLot.getParkingId());
            entryRecord.setVehicleNumber(vehicle.getVehicleNumber());
            entryRecord.setSpaceNumber(occupiedSpace.getSpaceNumber());
            entryRecord.setEntryTime(LocalDateTime.now());
            entryRecord.setEntryStatus("parked");
            entryRecord.setVehicleType(effectiveVehicleType);

            entryRecordRepository.save(entryRecord);

            vehicleService.updateVehicleStatus(vehicle.getVehicleId(), "parked");

            statisticsService.incrementEntryCount();

            historyService.recordEntry(entryRecord);

            EntryResponse response = new EntryResponse();
            response.setEntryId(entryRecord.getEntryId());
            response.setSpaceNumber(occupiedSpace.getSpaceNumber());
            response.setSpaceId(occupiedSpace.getSpaceId());
            response.setVehicleNumber(vehicle.getVehicleNumber());
            response.setEntryTime(entryRecord.getEntryTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            response.setVehicleType(effectiveVehicleType);
            response.setLockTimeoutSeconds(parkingSpaceService.getLockTimeoutByVehicleType(effectiveVehicleType));

            return response;

        } catch (Exception e) {
            parkingSpaceService.releaseLock(lockedSpace.getSpaceId());
            throw e;
        }
    }

    public EntryRecord getEntryById(String entryId) {
        return entryRecordRepository.findByEntryId(entryId)
                .orElseThrow(() -> new ParkingException(404, "入场记录不存在: " + entryId));
    }

    public List<EntryRecord> getAllEntries() {
        return entryRecordRepository.findAll();
    }

    public List<EntryRecord> getActiveEntries() {
        return entryRecordRepository.findByEntryStatus("parked");
    }

    public List<EntryRecord> getActiveEntriesByVehicleType(String vehicleType) {
        if (vehicleType == null) {
            return getActiveEntries();
        }
        return entryRecordRepository.findByEntryStatusAndVehicleType("parked", vehicleType.toLowerCase());
    }

    @Transactional
    public EntryRecord updateEntryStatus(String entryId, String status) {
        EntryRecord entry = getEntryById(entryId);
        entry.setEntryStatus(status);
        return entryRecordRepository.save(entry);
    }

    public long countActiveEntriesByVehicleType(String vehicleType) {
        if (vehicleType == null) {
            return entryRecordRepository.countByEntryStatus("parked");
        }
        return entryRecordRepository.countByEntryStatusAndVehicleType("parked", vehicleType.toLowerCase());
    }
}
