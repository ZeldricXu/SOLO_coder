package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.entity.BoxOfficeStat;
import com.movie.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        return ApiResponse.success(analysisService.getOverallStats());
    }

    @GetMapping("/date/{date}")
    public ApiResponse<List<BoxOfficeStat>> getByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(analysisService.getStatsByDate(date));
    }

    @GetMapping("/movie/{movieId}")
    public ApiResponse<Map<String, Object>> getByMovie(@PathVariable String movieId) {
        return ApiResponse.success(analysisService.getMovieStats(movieId));
    }

    @GetMapping("/cinema/{cinemaId}")
    public ApiResponse<List<BoxOfficeStat>> getByCinema(@PathVariable String cinemaId) {
        return ApiResponse.success(analysisService.getStatsByCinema(cinemaId));
    }
}
