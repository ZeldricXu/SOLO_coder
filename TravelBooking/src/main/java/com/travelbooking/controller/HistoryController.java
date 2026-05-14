package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.TravelHistory;
import com.travelbooking.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping
    public ApiResponse<List<TravelHistory>> getAllHistory() {
        return ApiResponse.success(historyService.getAllHistory());
    }

    @GetMapping("/reference/{referenceId}")
    public ApiResponse<List<TravelHistory>> getHistoryByReferenceId(@PathVariable String referenceId) {
        return ApiResponse.success(historyService.getHistoryByReferenceId(referenceId));
    }

    @GetMapping("/type/{recordType}")
    public ApiResponse<List<TravelHistory>> getHistoryByType(@PathVariable String recordType) {
        return ApiResponse.success(historyService.getHistoryByRecordType(recordType));
    }
}
