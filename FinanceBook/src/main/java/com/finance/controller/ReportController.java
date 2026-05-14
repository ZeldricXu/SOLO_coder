package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.dto.ReportQueryRequest;
import com.finance.dto.ReportQueryResponse;
import com.finance.entity.Report;
import com.finance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/query")
    public ApiResponse<ReportQueryResponse> queryReport(
            @RequestParam(required = false) String account_id,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String period) {

        ReportQueryRequest request = ReportQueryRequest.builder()
                .account_id(account_id)
                .start_date(start_date)
                .end_date(end_date)
                .period(period)
                .build();

        ReportQueryResponse response = reportService.queryReport(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<List<Report>> getReportsByAccount(@PathVariable String accountId) {
        List<Report> reports = reportService.getReportsByAccount(accountId);
        return ApiResponse.success(reports);
    }

    @GetMapping("/{reportId}")
    public ApiResponse<Report> getReport(@PathVariable String reportId) {
        Report report = reportService.getReportById(reportId);
        return ApiResponse.success(report);
    }
}
