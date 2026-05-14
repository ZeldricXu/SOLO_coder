package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.entity.TransactionStatusLog;
import com.paycenter.service.TransactionStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @Autowired
    private TransactionStatusService transactionStatusService;

    @GetMapping("/transaction/{transactionId}/history")
    public ApiResponse<List<TransactionStatusLog>> getStatusHistory(@PathVariable String transactionId) {
        List<TransactionStatusLog> history = transactionStatusService.getStatusHistory(transactionId);
        return ApiResponse.success(history);
    }
}
