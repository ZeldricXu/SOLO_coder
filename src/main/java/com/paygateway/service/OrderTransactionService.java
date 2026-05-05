package com.paygateway.service;

import com.paygateway.entity.PaymentOrder;
import com.paygateway.enums.OrderStatus;
import com.paygateway.exception.BusinessException;
import com.paygateway.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransactionService {
    
    private final PaymentOrderRepository paymentOrderRepository;
    
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentOrder findOrderForUpdate(String orderId) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            log.error("订单不存在：orderId={}", orderId);
            throw new BusinessException(404, "订单不存在：" + orderId);
        }
        return orderOpt.get();
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentOrder markOrderAsPaid(String orderId, String channelOrderNo, LocalDateTime paidAt) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            log.error("订单不存在：orderId={}", orderId);
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
        
        PaymentOrder savedOrder = paymentOrderRepository.save(order);
        
        log.info("订单标记为已支付：orderId={}, channelOrderNo={}", orderId, channelOrderNo);
        
        return savedOrder;
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
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
    
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentOrder findByChannelOrderNoForUpdate(String channelOrderNo) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByChannelOrderNo(channelOrderNo);
        if (orderOpt.isEmpty()) {
            log.error("根据渠道订单号未找到订单：channelOrderNo={}", channelOrderNo);
            return null;
        }
        return orderOpt.get();
    }
    
    @Transactional(readOnly = true)
    public Optional<PaymentOrder> findOrder(String orderId) {
        return paymentOrderRepository.findByOrderId(orderId);
    }
}
