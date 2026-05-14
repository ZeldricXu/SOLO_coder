package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.entity.HistoryRecord;
import com.survey.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/survey/{surveyId}")
    public ApiResponse<List<HistoryRecord>> getSurveyHistory(@PathVariable String surveyId) {
        List<HistoryRecord> records = historyService.getSurveyHistory(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/answer/{answerId}")
    public ApiResponse<List<HistoryRecord>> getAnswerHistory(@PathVariable String answerId) {
        List<HistoryRecord> records = historyService.getAnswerHistory(answerId);
        return ApiResponse.success(records);
    }

    @GetMapping("/type/{businessType}")
    public ApiResponse<List<HistoryRecord>> getHistoryByType(@PathVariable String businessType) {
        List<HistoryRecord> records = historyService.getHistoryByType(businessType);
        return ApiResponse.success(records);
    }

    @GetMapping
    public ApiResponse<List<HistoryRecord>> getHistory(
            @RequestParam String businessType,
            @RequestParam String businessId) {
        List<HistoryRecord> records = historyService.getHistory(businessType, businessId);
        return ApiResponse.success(records);
    }
}
