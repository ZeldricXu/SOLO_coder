package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.entity.ParkingSpace;
import com.parking.service.ParkingSpaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/spaces")
public class ParkingSpaceController {

    @Autowired
    private ParkingSpaceService parkingSpaceService;

    @PostMapping("/create")
    public ApiResponse<ParkingSpace> createParkingSpace(@RequestBody Map<String, Object> request) {
        String parkingId = (String) request.get("parkingId");
        String spaceNumber = (String) request.get("spaceNumber");
        String spaceType = (String) request.get("spaceType");
        Double spacePrice = request.get("spacePrice") != null ? ((Number) request.get("spacePrice")).doubleValue() : 0.0;

        if (parkingId == null || spaceNumber == null) {
            return ApiResponse.error(400, "停车场ID和车位号不能为空");
        }

        ParkingSpace space = parkingSpaceService.createParkingSpace(parkingId, spaceNumber, spaceType, spacePrice);
        return ApiResponse.success(space);
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<ParkingSpace> getParkingSpace(@PathVariable String spaceId) {
        ParkingSpace space = parkingSpaceService.getParkingSpaceById(spaceId);
        return ApiResponse.success(space);
    }

    @GetMapping("/list")
    public ApiResponse<List<ParkingSpace>> listParkingSpaces() {
        List<ParkingSpace> spaces = parkingSpaceService.getAllSpaces();
        return ApiResponse.success(spaces);
    }

    @GetMapping("/available/{parkingId}")
    public ApiResponse<List<ParkingSpace>> listAvailableSpaces(@PathVariable String parkingId) {
        List<ParkingSpace> availableSpaces = parkingSpaceService.getAvailableSpaces(parkingId);
        return ApiResponse.success(availableSpaces);
    }

    @GetMapping("/count/{parkingId}")
    public ApiResponse<Map<String, Object>> countSpaces(@PathVariable String parkingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("total", parkingSpaceService.countTotalSpaces(parkingId));
        result.put("available", parkingSpaceService.countAvailableSpaces(parkingId));
        result.put("occupied", parkingSpaceService.countTotalSpaces(parkingId) - parkingSpaceService.countAvailableSpaces(parkingId));
        return ApiResponse.success(result);
    }

    @PutMapping("/{spaceId}/status")
    public ApiResponse<ParkingSpace> updateSpaceStatus(@PathVariable String spaceId, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        if (status == null) {
            return ApiResponse.error(400, "状态不能为空");
        }
        ParkingSpace space = parkingSpaceService.updateSpaceStatus(spaceId, status);
        return ApiResponse.success(space);
    }

    @DeleteMapping("/{spaceId}")
    public ApiResponse<Void> deleteParkingSpace(@PathVariable String spaceId) {
        parkingSpaceService.deleteParkingSpace(spaceId);
        return ApiResponse.success(null);
    }
}
