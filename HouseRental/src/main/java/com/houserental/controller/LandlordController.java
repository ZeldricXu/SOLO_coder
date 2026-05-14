package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.LandlordDTO;
import com.houserental.entity.Landlord;
import com.houserental.service.LandlordService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/landlords")
public class LandlordController {

    @Autowired
    private LandlordService landlordService;

    @PostMapping("/create")
    public ApiResponse<Landlord> createLandlord(@Valid @RequestBody LandlordDTO dto) {
        Landlord landlord = landlordService.createLandlord(dto);
        return ApiResponse.success(landlord);
    }

    @GetMapping("/{landlordId}")
    public ApiResponse<Landlord> getLandlordById(@PathVariable String landlordId) {
        Landlord landlord = landlordService.getLandlordById(landlordId);
        return ApiResponse.success(landlord);
    }

    @PutMapping("/{landlordId}")
    public ApiResponse<Landlord> updateLandlord(@PathVariable String landlordId, @RequestBody LandlordDTO dto) {
        Landlord landlord = landlordService.updateLandlord(landlordId, dto);
        return ApiResponse.success(landlord);
    }

    @GetMapping("/list")
    public ApiResponse<List<Landlord>> getAllLandlords() {
        List<Landlord> landlords = landlordService.getAllLandlords();
        return ApiResponse.success(landlords);
    }

    @GetMapping("/active")
    public ApiResponse<List<Landlord>> getActiveLandlords() {
        List<Landlord> landlords = landlordService.getActiveLandlords();
        return ApiResponse.success(landlords);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getLandlordStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", landlordService.countTotalLandlords());
        stats.put("active", landlordService.countActiveLandlords());
        return ApiResponse.success(stats);
    }
}
