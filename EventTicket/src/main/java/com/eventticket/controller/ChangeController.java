package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.dto.ChangeRequest;
import com.eventticket.entity.ChangeRecord;
import com.eventticket.service.ChangeService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {

    @Autowired
    private ChangeService changeService;

    @PostMapping("/refund")
    public ApiResponse<ChangeRecord> processRefund(@Valid @RequestBody ChangeRequest request) {
        try {
            ChangeRecord changeRecord = changeService.processRefund(request);
            return ApiResponse.success(changeRecord);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/exchange")
    public ApiResponse<ChangeRecord> processExchange(@Valid @RequestBody ChangeRequest request) {
        try {
            ChangeRecord changeRecord = changeService.processExchange(request);
            return ApiResponse.success(changeRecord);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/{changeId}")
    public ApiResponse<ChangeRecord> getChangeRecordById(@PathVariable String changeId) {
        Optional<ChangeRecord> changeRecord = changeService.getChangeRecordById(changeId);
        if (changeRecord.isPresent()) {
            return ApiResponse.success(changeRecord.get());
        }
        return ApiResponse.error(404, "退改记录不存在");
    }

    @GetMapping("/ticket/{ticketId}")
    public ApiResponse<List<ChangeRecord>> getChangeRecordsByTicketId(@PathVariable String ticketId) {
        List<ChangeRecord> changeRecords = changeService.getChangeRecordsByTicketId(ticketId);
        return ApiResponse.success(changeRecords);
    }

    @GetMapping("/ticket/{ticketId}/refunds")
    public ApiResponse<List<ChangeRecord>> getRefundsByTicketId(@PathVariable String ticketId) {
        List<ChangeRecord> refunds = changeService.getRefundsByTicketId(ticketId);
        return ApiResponse.success(refunds);
    }

    @GetMapping("/ticket/{ticketId}/exchanges")
    public ApiResponse<List<ChangeRecord>> getExchangesByTicketId(@PathVariable String ticketId) {
        List<ChangeRecord> exchanges = changeService.getExchangesByTicketId(ticketId);
        return ApiResponse.success(exchanges);
    }
}
