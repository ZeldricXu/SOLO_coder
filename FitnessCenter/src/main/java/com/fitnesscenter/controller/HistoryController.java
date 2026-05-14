package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.History;
import com.fitnesscenter.service.HistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/member/{memberId}")
    public ApiResponse<List<History>> getHistoryByMemberId(@PathVariable String memberId) {
        List<History> historyList = historyService.getHistoryByMemberId(memberId);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/member/{memberId}/type/{actionType}")
    public ApiResponse<List<History>> getHistoryByMemberIdAndType(
            @PathVariable String memberId,
            @PathVariable String actionType) {
        List<History> historyList = historyService.getHistoryByMemberIdAndActionType(memberId, actionType);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/type/{actionType}")
    public ApiResponse<List<History>> getHistoryByType(@PathVariable String actionType) {
        List<History> historyList = historyService.getHistoryByActionType(actionType);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/related/{relatedId}")
    public ApiResponse<List<History>> getHistoryByRelatedId(@PathVariable String relatedId) {
        List<History> historyList = historyService.getHistoryByRelatedId(relatedId);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/member/{memberId}/this-month")
    public ApiResponse<List<History>> getMemberHistoryThisMonth(@PathVariable String memberId) {
        List<History> historyList = historyService.getMemberHistoryThisMonth(memberId);
        return ApiResponse.success(historyList);
    }

    @GetMapping
    public ApiResponse<List<History>> getAllHistory() {
        List<History> historyList = historyService.getAllHistory();
        return ApiResponse.success(historyList);
    }

    @GetMapping("/training/{memberId}")
    public ApiResponse<List<History>> queryTrainingHistory(@PathVariable String memberId) {
        List<History> historyList = historyService.queryTrainingHistory(memberId);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/booking/{memberId}")
    public ApiResponse<List<History>> queryBookingHistory(@PathVariable String memberId) {
        List<History> historyList = historyService.queryBookingHistory(memberId);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/plan/{memberId}")
    public ApiResponse<List<History>> queryPlanHistory(@PathVariable String memberId) {
        List<History> historyList = historyService.queryPlanHistory(memberId);
        return ApiResponse.success(historyList);
    }
}
