package com.paygateway.controller;

import com.paygateway.dto.ApiResponse;
import com.paygateway.dto.CreatePaymentRequest;
import com.paygateway.dto.CreatePaymentResponse;
import com.paygateway.dto.OrderQueryResponse;
import com.paygateway.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/pay")
@RequiredArgsConstructor
public class PaymentController {
    
    private final OrderService orderService;
    
    @PostMapping("/create")
    public ApiResponse<CreatePaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        log.info("收到支付下单请求：merchantId={}, merchantOrderNo={}, amount={}, channel={}", 
                request.getMerchantId(), request.getMerchantOrderNo(), request.getAmount(), request.getChannel());
        
        CreatePaymentResponse response = orderService.createOrder(request);
        
        log.info("支付下单成功：gatewayOrderId={}", response.getGatewayOrderId());
        
        return ApiResponse.success(response);
    }
    
    @GetMapping("/query")
    public ApiResponse<OrderQueryResponse> queryOrder(
            @RequestParam(required = false) String gatewayOrderId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String merchantOrderNo) {
        
        log.info("收到订单查询请求：gatewayOrderId={}, merchantId={}, merchantOrderNo={}", 
                gatewayOrderId, merchantId, merchantOrderNo);
        
        OrderQueryResponse response = orderService.queryOrder(gatewayOrderId, merchantId, merchantOrderNo);
        
        return ApiResponse.success(response);
    }
}
