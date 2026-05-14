package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.entity.ParkingLot;
import com.parking.service.ParkingLotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/parking-lots")
public class ParkingLotController {

    @Autowired
    private ParkingLotService parkingLotService;

    @PostMapping("/create")
    public ApiResponse<ParkingLot> createParkingLot(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String address = (String) request.get("address");
        Integer totalSpaces = (Integer) request.get("totalSpaces");
        Double hourlyRate = request.get("hourlyRate") != null ? ((Number) request.get("hourlyRate")).doubleValue() : 10.0;
        String chargingType = (String) request.get("chargingType");
        Double fixedFee = request.get("fixedFee") != null ? ((Number) request.get("fixedFee")).doubleValue() : null;

        if (name == null || totalSpaces == null) {
            return ApiResponse.error(400, "停车场名称和总车位数不能为空");
        }

        ParkingLot parkingLot = parkingLotService.createParkingLot(name, address, totalSpaces, hourlyRate, chargingType, fixedFee);
        return ApiResponse.success(parkingLot);
    }

    @GetMapping("/{parkingId}")
    public ApiResponse<ParkingLot> getParkingLot(@PathVariable String parkingId) {
        ParkingLot parkingLot = parkingLotService.getParkingLotById(parkingId);
        return ApiResponse.success(parkingLot);
    }

    @GetMapping("/list")
    public ApiResponse<List<ParkingLot>> listParkingLots() {
        List<ParkingLot> parkingLots = parkingLotService.getAllParkingLots();
        return ApiResponse.success(parkingLots);
    }

    @PutMapping("/{parkingId}")
    public ApiResponse<ParkingLot> updateParkingLot(@PathVariable String parkingId, @RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String address = (String) request.get("address");
        Integer totalSpaces = request.get("totalSpaces") != null ? ((Number) request.get("totalSpaces")).intValue() : null;
        Double hourlyRate = request.get("hourlyRate") != null ? ((Number) request.get("hourlyRate")).doubleValue() : null;
        String chargingType = (String) request.get("chargingType");
        Double fixedFee = request.get("fixedFee") != null ? ((Number) request.get("fixedFee")).doubleValue() : null;

        ParkingLot parkingLot = parkingLotService.updateParkingLot(parkingId, name, address, totalSpaces, hourlyRate, chargingType, fixedFee);
        return ApiResponse.success(parkingLot);
    }

    @DeleteMapping("/{parkingId}")
    public ApiResponse<Void> deleteParkingLot(@PathVariable String parkingId) {
        parkingLotService.deleteParkingLot(parkingId);
        return ApiResponse.success(null);
    }
}
