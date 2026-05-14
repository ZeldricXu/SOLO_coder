package com.parking.service;

import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.entity.ReservationRecord;
import com.parking.entity.Vehicle;
import com.parking.exception.ParkingException;
import com.parking.repository.ReservationRecordRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReservationService {

    @Autowired
    private ReservationRecordRepository reservationRepository;

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
    public ReservationRecord createReservation(String vehicleNumber, String parkingId, LocalDateTime expectedStartTime, LocalDateTime expectedEndTime) {
        Vehicle vehicle = vehicleService.getVehicleByNumber(vehicleNumber);
        if (vehicle == null) {
            throw new ParkingException(400, "车辆不存在，无法预约");
        }

        ParkingLot parkingLot = parkingLotService.getParkingLotById(parkingId);

        List<ParkingSpace> availableSpaces = parkingSpaceService.getAvailableSpaces(parkingId);
        if (availableSpaces.isEmpty()) {
            throw new ParkingException(400, "停车场暂无可用车位");
        }

        ParkingSpace space = availableSpaces.get(0);

        ReservationRecord reservation = new ReservationRecord();
        reservation.setReserveId(IdGenerator.generateReserveId());
        reservation.setSpaceId(space.getSpaceId());
        reservation.setVehicleId(vehicle.getVehicleId());
        reservation.setParkingId(parkingLot.getParkingId());
        reservation.setVehicleNumber(vehicle.getVehicleNumber());
        reservation.setSpaceNumber(space.getSpaceNumber());
        reservation.setReserveTime(LocalDateTime.now());
        reservation.setExpectedStartTime(expectedStartTime);
        reservation.setExpectedEndTime(expectedEndTime);
        reservation.setReserveStatus("confirmed");

        reservationRepository.save(reservation);

        parkingSpaceService.updateSpaceStatus(space.getSpaceId(), "reserved");

        statisticsService.incrementReservationCount();

        historyService.recordReservation(reservation);

        return reservation;
    }

    public ReservationRecord getReservationById(String reserveId) {
        return reservationRepository.findByReserveId(reserveId)
                .orElseThrow(() -> new ParkingException(404, "预约记录不存在: " + reserveId));
    }

    public List<ReservationRecord> getAllReservations() {
        return reservationRepository.findAll();
    }

    public List<ReservationRecord> getReservationsByVehicle(String vehicleId) {
        return reservationRepository.findByVehicleId(vehicleId);
    }

    public List<ReservationRecord> getReservationsByParking(String parkingId) {
        return reservationRepository.findByParkingId(parkingId);
    }

    @Transactional
    public ReservationRecord cancelReservation(String reserveId) {
        ReservationRecord reservation = getReservationById(reserveId);

        if ("cancelled".equals(reservation.getReserveStatus())) {
            throw new ParkingException(400, "预约已取消");
        }

        reservation.setReserveStatus("cancelled");
        reservationRepository.save(reservation);

        parkingSpaceService.updateSpaceStatus(reservation.getSpaceId(), "available");

        return reservation;
    }

    @Transactional
    public ReservationRecord completeReservation(String reserveId) {
        ReservationRecord reservation = getReservationById(reserveId);
        reservation.setReserveStatus("completed");
        return reservationRepository.save(reservation);
    }
}
