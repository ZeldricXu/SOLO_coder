package com.paygateway.controller;

import com.paygateway.dto.ApiResponse;
import com.paygateway.dto.RefundRequest;
import com.paygateway.dto.RefundResponse;
import com.paygateway.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/refund")
@RequiredArgsConstructor
public class RefundController {
    
    private final RefundService refundService;
    
    @PostMapping("/create")
    public ApiResponse<RefundResponse> createRefund(@Valid @RequestBody RefundRequest request) {
        log.info("收到退款请求：merchantId={}, merchantRefundNo={}, amount={}", 
                request.getMerchantId(), request.getMerchantRefundNo(), request.getAmount());
        
        RefundResponse response = refundService.createRefund(request);
        
        log.info("退款创建成功：gatewayRefundId={}", response.getGatewayRefundId());
        
        return ApiResponse.success(response);
    }
    
    @GetMapping("/query")
    public ApiResponse<RefundResponse> queryRefund(
            @RequestParam(required = false) String refundId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String merchantRefundNo) {
        
        log.info("收到退款查询请求：refundId={}, merchantId={}, merchantRefundNo={}", 
                refundId, merchantId, merchantRefundNo);
        
        RefundResponse response = refundService.queryRefund(refundId, merchantId, merchantRefundNo);
        
        return ApiResponse.success(response);
    }
}
