package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.HotelStat;
import com.hotelbooking.service.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/hotels/{hotelId}/stats")
    public ResponseEntity<ApiResponse<HotelStat>> getHotelStats(
            @PathVariable String hotelId,
            @RequestParam(required = false) String month) {
        if (month == null || month.isEmpty()) {
            month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        HotelStat stat = analysisService.getHotelStats(hotelId, month);
        return ResponseEntity.ok(ApiResponse.success(stat));
    }

    @GetMapping("/hotels/{hotelId}/history")
    public ResponseEntity<ApiResponse<List<HotelStat>>> getHotelStatsHistory(@PathVariable String hotelId) {
        List<HotelStat> stats = analysisService.getHotelStatsByHotel(hotelId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
