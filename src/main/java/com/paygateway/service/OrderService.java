package com.paygateway.service;

import com.paygateway.dto.CreatePaymentRequest;
import com.paygateway.dto.CreatePaymentResponse;
import com.paygateway.dto.OrderQueryResponse;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.entity.PaymentOrder;
import com.paygateway.enums.OrderStatus;
import com.paygateway.exception.BusinessException;
import com.paygateway.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final PaymentOrderRepository paymentOrderRepository;
    private final ChannelConfigService channelConfigService;
    private final PaymentAdapterFactory paymentAdapterFactory;
    
    @Transactional(rollbackFor = Exception.class)
    public CreatePaymentResponse createOrder(CreatePaymentRequest request) {
        String merchantId = request.getMerchantId();
        String channel = request.getChannel();
        
        if (paymentOrderRepository.findByMerchantIdAndMerchantOrderNo(merchantId, request.getMerchantOrderNo()).isPresent()) {
            throw new BusinessException(400, "商户订单号已存在");
        }
        
        ChannelConfig config = channelConfigService.getByMerchantIdAndChannel(merchantId, channel);
        
        PaymentAdapter adapter = paymentAdapterFactory.getAdapter(channel);
        
        String orderId = generateOrderId();
        
        CreatePaymentResponse channelResponse = adapter.createOrder(request, config, orderId);
        
        PaymentOrder order = new PaymentOrder();
        order.setOrderId(orderId);
        order.setMerchantId(merchantId);
        order.setMerchantOrderNo(request.getMerchantOrderNo());
        order.setAmount(request.getAmount());
        order.setCurrency("CNY");
        order.setChannel(channel);
        order.setStatus(OrderStatus.PENDING);
        order.setProductDesc(request.getProductDesc());
        order.setNotifyUrl(request.getNotifyUrl());
        order.setCallbackReceived(false);
        
        paymentOrderRepository.save(order);
        
        channelResponse.setGatewayOrderId(orderId);
        
        log.info("订单创建成功：orderId={}, merchantOrderNo={}", orderId, request.getMerchantOrderNo());
        
        return channelResponse;
    }
    
    public OrderQueryResponse queryOrder(String gatewayOrderId, String merchantId, String merchantOrderNo) {
        Optional<PaymentOrder> orderOpt;
        
        if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
            orderOpt = paymentOrderRepository.findByOrderId(gatewayOrderId);
        } else if (merchantId != null && merchantOrderNo != null) {
            orderOpt = paymentOrderRepository.findByMerchantIdAndMerchantOrderNo(merchantId, merchantOrderNo);
        } else {
            throw new BusinessException(400, "请提供网关订单号或商户订单号");
        }
        
        if (orderOpt.isEmpty()) {
            throw new BusinessException(404, "订单不存在");
        }
        
        PaymentOrder order = orderOpt.get();
        
        OrderQueryResponse response = new OrderQueryResponse();
        response.setGatewayOrderId(order.getOrderId());
        response.setMerchantOrderNo(order.getMerchantOrderNo());
        response.setChannelOrderNo(order.getChannelOrderNo());
        response.setStatus(order.getStatus().getCode());
        response.setAmount(order.getAmount());
        response.setCurrency(order.getCurrency());
        response.setChannel(order.getChannel());
        response.setPaidAt(order.getPaidAt());
        response.setCreatedAt(order.getCreatedAt());
        
        return response;
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateOrderStatus(String orderId, OrderStatus status, String channelOrderNo) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            log.error("订单不存在：orderId={}", orderId);
            return;
        }
        
        PaymentOrder order = orderOpt.get();
        
        if (OrderStatus.isPaid(order.getStatus())) {
            log.info("订单已支付，跳过状态更新：orderId={}", orderId);
            return;
        }
        
        order.updateStatus(status);
        if (channelOrderNo != null) {
            order.setChannelOrderNo(channelOrderNo);
        }
        if (OrderStatus.PAID.equals(status)) {
            order.setPaidAt(LocalDateTime.now());
        }
        
        paymentOrderRepository.save(order);
        log.info("订单状态更新成功：orderId={}, status={}", orderId, status);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateOrderByChannelOrderNo(String channelOrderNo, OrderStatus status, LocalDateTime paidAt) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByChannelOrderNo(channelOrderNo);
        if (orderOpt.isEmpty()) {
            log.error("根据渠道订单号未找到订单：channelOrderNo={}", channelOrderNo);
            return;
        }
        
        PaymentOrder order = orderOpt.get();
        
        if (OrderStatus.isPaid(order.getStatus())) {
            log.info("订单已支付，跳过状态更新：orderId={}, channelOrderNo={}", order.getOrderId(), channelOrderNo);
            return;
        }
        
        order.updateStatus(status);
        order.setCallbackReceived(true);
        if (paidAt != null) {
            order.setPaidAt(paidAt);
        } else if (OrderStatus.PAID.equals(status)) {
            order.setPaidAt(LocalDateTime.now());
        }
        
        paymentOrderRepository.save(order);
        log.info("订单状态更新成功：orderId={}, status={}", order.getOrderId(), status);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public PaymentOrder markOrderAsPaid(String orderId, String channelOrderNo, LocalDateTime paidAt) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            throw new BusinessException(404, "订单不存在：" + orderId);
        }
        
        PaymentOrder order = orderOpt.get();
        
        if (order.isPaid()) {
            log.info("订单已支付，跳过处理：orderId={}", orderId);
            return order;
        }
        
        order.updateStatus(OrderStatus.PAID);
        order.setCallbackReceived(true);
        order.setChannelOrderNo(channelOrderNo);
        order.setPaidAt(paidAt != null ? paidAt : LocalDateTime.now());
        
        return paymentOrderRepository.save(order);
    }
    
    private String generateOrderId() {
        return "PAY" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
