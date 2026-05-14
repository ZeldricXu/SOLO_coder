package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.entity.Settlement;
import com.paycenter.entity.Transaction;
import com.paycenter.entity.TransactionStat;
import com.paycenter.service.QueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    @Autowired
    private QueryService queryService;

    @GetMapping("/transactions")
    public ApiResponse<List<Transaction>> queryTransactions(
            @RequestParam String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        
        if (start == null) {
            start = LocalDateTime.now().minusDays(30);
        }
        if (end == null) {
            end = LocalDateTime.now();
        }
        
        List<Transaction> transactions = queryService.queryTransactions(merchantId, start, end);
        return ApiResponse.success(transactions);
    }

    @GetMapping("/transaction/{transactionId}")
    public ApiResponse<Map<String, Object>> getTransactionDetail(@PathVariable String transactionId) {
        Map<String, Object> detail = queryService.getTransactionDetail(transactionId);
        return ApiResponse.success(detail);
    }

    @GetMapping("/stats")
    public ApiResponse<List<TransactionStat>> queryStats(
            @RequestParam String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        
        if (start == null) {
            start = LocalDate.now().minusDays(30);
        }
        if (end == null) {
            end = LocalDate.now();
        }
        
        List<TransactionStat> stats = queryService.queryStats(merchantId, start, end);
        return ApiResponse.success(stats);
    }

    @GetMapping("/account/{merchantId}")
    public ApiResponse<Map<String, Object>> getAccountSummary(@PathVariable String merchantId) {
        Map<String, Object> summary = queryService.getAccountSummary(merchantId);
        return ApiResponse.success(summary);
    }
}
