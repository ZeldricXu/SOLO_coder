package com.invoice.mgmt.api.controller;

import com.invoice.mgmt.common.dto.*;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.response.ApiResponse;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.reimburse.service.InvoiceReimburseService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.verify.service.InvoiceVerifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/invoices")
@Validated
public class InvoiceController {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceController.class);

    @Autowired
    private InvoiceIssueService invoiceIssueService;

    @Autowired
    private InvoiceVerifyService invoiceVerifyService;

    @Autowired
    private InvoiceReimburseService invoiceReimburseService;

    @Autowired
    private InvoiceStatisticsService invoiceStatisticsService;

    @PostMapping("/issue")
    public ApiResponse<InvoiceIssueResponse> issue(@Valid @RequestBody InvoiceIssueRequest request) {
        logger.info("收到开票请求: type={}, buyer={}, amount={}",
                request.getInvoiceType(), request.getBuyerName(), request.getInvoiceAmount());
        InvoiceIssueResponse response = invoiceIssueService.issue(request);
        return ApiResponse.success("开票成功", response);
    }

    @PostMapping("/verify")
    public ApiResponse<InvoiceVerifyResponse> verify(@Valid @RequestBody InvoiceVerifyRequest request) {
        logger.info("收到验证请求: invoiceNo={}, invoiceCode={}, type={}",
                request.getInvoiceNo(), request.getInvoiceCode(), request.getVerifyType());
        InvoiceVerifyResponse response = invoiceVerifyService.verify(request);
        return ApiResponse.success("验证完成", response);
    }

    @PostMapping("/reimburse")
    public ApiResponse<InvoiceReimburseResponse> reimburse(@Valid @RequestBody InvoiceReimburseRequest request) {
        logger.info("收到报销申请: invoiceId={}, user={}",
                request.getInvoiceId(), request.getReimburseUser());
        InvoiceReimburseResponse response = invoiceReimburseService.apply(request);
        return ApiResponse.success("报销申请提交成功", response);
    }

    @GetMapping("/statistics")
    public ApiResponse<InvoiceStatisticsDTO> statistics(
            @RequestParam(value = "month", required = false) String month) {
        InvoiceStatisticsDTO stat;
        if (month == null || month.isEmpty()) {
            stat = invoiceStatisticsService.getCurrentMonth();
        } else {
            stat = invoiceStatisticsService.getByMonth(month);
        }
        return ApiResponse.success(stat);
    }

    @GetMapping("/{invoiceId}")
    public ApiResponse<com.invoice.mgmt.common.entity.Invoice> getById(@PathVariable String invoiceId) {
        com.invoice.mgmt.common.entity.Invoice invoice = invoiceIssueService.getById(invoiceId);
        return ApiResponse.success(invoice);
    }

    @ExceptionHandler(InvoiceException.class)
    public ApiResponse<?> handleInvoiceException(InvoiceException e) {
        logger.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception e) {
        logger.error("系统异常", e);
        return ApiResponse.error(500, "系统内部错误");
    }
}
