package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.entity.History;
import com.crm.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/histories")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<History>> getCustomerHistory(@PathVariable String customerId) {
        List<History> histories = historyService.getCustomerHistory(customerId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/type/{historyType}")
    public ApiResponse<List<History>> getHistoryByType(@PathVariable String historyType) {
        List<History> histories = historyService.getHistoryByType(historyType);
        return ApiResponse.success(histories);
    }

    @GetMapping("/related/{relatedId}")
    public ApiResponse<List<History>> getHistoryByRelatedId(@PathVariable String relatedId) {
        List<History> histories = historyService.getHistoryByRelatedId(relatedId);
        return ApiResponse.success(histories);
    }
}
