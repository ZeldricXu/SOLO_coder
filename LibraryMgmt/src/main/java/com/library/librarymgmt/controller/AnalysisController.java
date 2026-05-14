package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.entity.BorrowStat;
import com.library.librarymgmt.service.AnalysisService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/stats/current")
    public ApiResponse<BorrowStat> getCurrentMonthStat() {
        return ApiResponse.success(analysisService.getOrCreateCurrentMonthStat());
    }

    @GetMapping("/stats/{month}")
    public ApiResponse<BorrowStat> getStatByMonth(@PathVariable String month) {
        Optional<BorrowStat> stat = analysisService.getStatByMonth(month);
        if (stat.isPresent()) {
            return ApiResponse.success(stat.get());
        }
        return ApiResponse.error(404, "统计数据不存在");
    }

    @GetMapping("/stats")
    public ApiResponse<List<BorrowStat>> getAllStats() {
        return ApiResponse.success(analysisService.getAllStats());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_books", analysisService.getTotalBooks());
        summary.put("total_readers", analysisService.getTotalReaders());
        summary.put("active_borrows", analysisService.getActiveBorrowsCount());
        summary.put("overdue_borrows", analysisService.getOverdueBorrowsCount());
        summary.put("waiting_reserves", analysisService.getWaitingReservesCount());
        return ApiResponse.success(summary);
    }
}
