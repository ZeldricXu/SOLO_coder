package com.parking.service;

import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.ReservationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    public void recordEntry(EntryRecord entryRecord) {
        logger.info("入场记录 - entryId: {}, vehicleNumber: {}, spaceNumber: {}, entryTime: {}",
                entryRecord.getEntryId(),
                entryRecord.getVehicleNumber(),
                entryRecord.getSpaceNumber(),
                entryRecord.getEntryTime());
    }

    public void recordExit(ExitRecord exitRecord, EntryRecord entryRecord) {
        logger.info("出场记录 - exitId: {}, entryId: {}, vehicleNumber: {}, parkingDuration: {}分钟, parkingFee: {}元",
                exitRecord.getExitId(),
                exitRecord.getEntryId(),
                entryRecord.getVehicleNumber(),
                exitRecord.getParkingDuration(),
                exitRecord.getParkingFee());
    }

    public void recordReservation(ReservationRecord reservation) {
        logger.info("预约记录 - reserveId: {}, vehicleNumber: {}, spaceNumber: {}, reserveTime: {}",
                reservation.getReserveId(),
                reservation.getVehicleNumber(),
                reservation.getSpaceNumber(),
                reservation.getReserveTime());
    }

    public void recordPayment(String settlementId, double amount, String paymentMethod, String status) {
        logger.info("支付记录 - settlementId: {}, amount: {}元, paymentMethod: {}, status: {}",
                settlementId, amount, paymentMethod, status);
    }
}
