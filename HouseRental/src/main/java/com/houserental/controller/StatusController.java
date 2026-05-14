package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.HouseStatusDTO;
import com.houserental.entity.House;
import com.houserental.service.StatusService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @Autowired
    private StatusService statusService;

    @PostMapping("/house/update")
    public ApiResponse<House> updateHouseStatus(@Valid @RequestBody HouseStatusDTO dto) {
        if (!statusService.isValidHouseStatus(dto.getStatus())) {
            return ApiResponse.error(400, "无效的房源状态");
        }
        House house = statusService.updateHouseStatus(dto.getHouseId(), dto.getStatus());
        return ApiResponse.success(house);
    }

    @GetMapping("/house/{houseId}")
    public ApiResponse<Map<String, Object>> getHouseStatusInfo(@PathVariable String houseId) {
        Map<String, Object> info = statusService.getHouseStatusInfo(houseId);
        return ApiResponse.success(info);
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSystemStatusSummary() {
        Map<String, Object> summary = statusService.getSystemStatusSummary();
        return ApiResponse.success(summary);
    }

    @PostMapping("/house/{houseId}/rent")
    public ApiResponse<House> markHouseAsRented(@PathVariable String houseId) {
        House house = statusService.markHouseAsRented(houseId);
        return ApiResponse.success(house);
    }

    @PostMapping("/house/{houseId}/available")
    public ApiResponse<House> markHouseAsAvailable(@PathVariable String houseId) {
        House house = statusService.markHouseAsAvailable(houseId);
        return ApiResponse.success(house);
    }

    @PostMapping("/house/{houseId}/offline")
    public ApiResponse<House> markHouseAsOffline(@PathVariable String houseId) {
        House house = statusService.markHouseAsOffline(houseId);
        return ApiResponse.success(house);
    }
}
