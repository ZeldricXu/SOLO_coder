package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.entity.ReservationRecord;
import com.parking.service.ReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    @Autowired
    private ReservationService reservationService;

    @PostMapping("/create")
    public ApiResponse<ReservationRecord> createReservation(@RequestBody Map<String, Object> request) {
        String vehicleNumber = (String) request.get("vehicleNumber");
        String parkingId = (String) request.get("parkingId");
        String expectedStartTimeStr = (String) request.get("expectedStartTime");
        String expectedEndTimeStr = (String) request.get("expectedEndTime");

        if (vehicleNumber == null || parkingId == null) {
            return ApiResponse.error(400, "车牌号码和停车场ID不能为空");
        }

        LocalDateTime expectedStartTime = null;
        LocalDateTime expectedEndTime = null;
        
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        if (expectedStartTimeStr != null) {
            expectedStartTime = LocalDateTime.parse(expectedStartTimeStr, formatter);
        }
        if (expectedEndTimeStr != null) {
            expectedEndTime = LocalDateTime.parse(expectedEndTimeStr, formatter);
        }

        ReservationRecord reservation = reservationService.createReservation(vehicleNumber, parkingId, expectedStartTime, expectedEndTime);
        return ApiResponse.success(reservation);
    }

    @GetMapping("/{reserveId}")
    public ApiResponse<ReservationRecord> getReservation(@PathVariable String reserveId) {
        ReservationRecord reservation = reservationService.getReservationById(reserveId);
        return ApiResponse.success(reservation);
    }

    @GetMapping("/list")
    public ApiResponse<List<ReservationRecord>> listReservations() {
        List<ReservationRecord> reservations = reservationService.getAllReservations();
        return ApiResponse.success(reservations);
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ApiResponse<List<ReservationRecord>> listReservationsByVehicle(@PathVariable String vehicleId) {
        List<ReservationRecord> reservations = reservationService.getReservationsByVehicle(vehicleId);
        return ApiResponse.success(reservations);
    }

    @PostMapping("/{reserveId}/cancel")
    public ApiResponse<ReservationRecord> cancelReservation(@PathVariable String reserveId) {
        ReservationRecord reservation = reservationService.cancelReservation(reserveId);
        return ApiResponse.success(reservation);
    }

    @PostMapping("/{reserveId}/complete")
    public ApiResponse<ReservationRecord> completeReservation(@PathVariable String reserveId) {
        ReservationRecord reservation = reservationService.completeReservation(reserveId);
        return ApiResponse.success(reservation);
    }
}
