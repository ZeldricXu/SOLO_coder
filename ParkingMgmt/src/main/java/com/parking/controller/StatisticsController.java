package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.entity.ParkingStatistics;
import com.parking.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @GetMapping("/current")
    public ApiResponse<ParkingStatistics> getCurrentMonthStatistics() {
        ParkingStatistics stats = statisticsService.getCurrentMonthStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/month/{statMonth}")
    public ApiResponse<ParkingStatistics> getStatisticsByMonth(@PathVariable String statMonth) {
        ParkingStatistics stats = statisticsService.getStatisticsByMonth(statMonth);
        return ApiResponse.success(stats);
    }

    @GetMapping("/occupancy/{parkingId}")
    public ApiResponse<Map<String, Object>> getOccupancyRate(@PathVariable String parkingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("parkingId", parkingId);
        result.put("occupancyRate", statisticsService.calculateOccupancyRate(parkingId));
        return ApiResponse.success(result);
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> getDashboardData(@RequestParam(required = false) String parkingId) {
        Map<String, Object> dashboard = new HashMap<>();
        ParkingStatistics currentStats = statisticsService.getCurrentMonthStatistics();
        
        dashboard.put("currentMonth", currentStats.getStatMonth());
        dashboard.put("entryCount", currentStats.getEntryCount());
        dashboard.put("exitCount", currentStats.getExitCount());
        dashboard.put("totalAmount", currentStats.getTotalAmount());
        dashboard.put("reservationCount", currentStats.getReservationCount());
        
        if (parkingId != null) {
            dashboard.put("occupancyRate", statisticsService.calculateOccupancyRate(parkingId));
        }
        
        return ApiResponse.success(dashboard);
    }
}
