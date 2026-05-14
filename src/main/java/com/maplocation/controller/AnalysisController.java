package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.model.LocationQueryCount;
import com.maplocation.model.LocationStatistics;
import com.maplocation.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/today")
    public ApiResponse<LocationStatistics> getTodayStatistics() {
        LocationStatistics stats = analysisService.getTodayStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/date/{date}")
    public ApiResponse<LocationStatistics> getStatisticsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocationStatistics stats = analysisService.getStatisticsByDate(date);
        if (stats == null) {
            return ApiResponse.error(404, "Statistics not found for date: " + date);
        }
        return ApiResponse.success(stats);
    }

    @GetMapping("/hot-locations")
    public ApiResponse<List<LocationQueryCount>> getHotLocations() {
        List<LocationQueryCount> hotLocations = analysisService.getHotLocations();
        return ApiResponse.success(hotLocations);
    }
}
