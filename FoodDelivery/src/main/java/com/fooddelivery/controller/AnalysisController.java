package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.Stat;
import com.fooddelivery.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Stat>>> getAllStats() {
        List<Stat> stats = analysisService.getAllStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/month/{month}")
    public ResponseEntity<ApiResponse<Stat>> getStatByMonth(@PathVariable String month) {
        Optional<Stat> stat = analysisService.getStatByMonth(month);
        if (stat.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(stat.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "该月份暂无统计数据"));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<Stat>> getCurrentMonthStat() {
        Optional<Stat> stat = analysisService.getCurrentMonthStat();
        if (stat.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(stat.get()));
        }
        return ResponseEntity.ok(ApiResponse.success(new Stat()));
    }
}
