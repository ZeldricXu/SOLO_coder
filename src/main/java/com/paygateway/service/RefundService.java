package com.paygateway.service;

import com.paygateway.dto.RefundRequest;
import com.paygateway.dto.RefundResponse;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.entity.PaymentOrder;
import com.paygateway.enums.OrderStatus;
import com.paygateway.entity.RefundRecord;
import com.paygateway.exception.BusinessException;
import com.paygateway.repository.PaymentOrderRepository;
import com.paygateway.repository.RefundRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {
    
    private final RefundRecordRepository refundRecordRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ChannelConfigService channelConfigService;
    private final PaymentAdapterFactory paymentAdapterFactory;
    
    @Transactional(rollbackFor = Exception.class)
    public RefundResponse createRefund(RefundRequest request) {
        String merchantId = request.getMerchantId();
        
        if (refundRecordRepository.findByMerchantIdAndMerchantRefundNo(merchantId, request.getMerchantRefundNo()).isPresent()) {
            throw new BusinessException(400, "商户退款单号已存在");
        }
        
        PaymentOrder order = findOrder(request);
        
        if (!order.canRefund()) {
            throw new BusinessException(400, "订单状态不可退款，当前状态：" + order.getStatus().getDescription());
        }
        
        if (request.getAmount().compareTo(order.getAmount()) > 0) {
            throw new BusinessException(400, "退款金额不能大于订单金额");
        }
        
        ChannelConfig config = channelConfigService.getByMerchantIdAndChannel(merchantId, order.getChannel());
        
        PaymentAdapter adapter = paymentAdapterFactory.getAdapter(order.getChannel());
        
        String refundId = generateRefundId();
        
        RefundResponse channelResponse = adapter.refund(request, config, refundId, order.getChannelOrderNo());
        
        RefundRecord record = new RefundRecord();
        record.setRefundId(refundId);
        record.setOrderId(order.getOrderId());
        record.setMerchantId(merchantId);
        record.setMerchantRefundNo(request.getMerchantRefundNo());
        record.setAmount(request.getAmount());
        record.setReason(request.getReason());
        record.setChannelRefundNo(channelResponse.getChannelRefundNo());
        record.setStatus("success");
        
        refundRecordRepository.save(record);
        
        if (request.getAmount().compareTo(order.getAmount()) == 0) {
            order.updateStatus(OrderStatus.REFUNDED);
        } else {
            order.updateStatus(OrderStatus.PARTIAL_REFUNDED);
        }
        paymentOrderRepository.save(order);
        
        channelResponse.setGatewayRefundId(refundId);
        
        log.info("退款创建成功：refundId={}, orderId={}", refundId, order.getOrderId());
        
        return channelResponse;
    }
    
    public RefundResponse queryRefund(String refundId, String merchantId, String merchantRefundNo) {
        Optional<RefundRecord> recordOpt;
        
        if (refundId != null && !refundId.isEmpty()) {
            recordOpt = refundRecordRepository.findByRefundId(refundId);
        } else if (merchantId != null && merchantRefundNo != null) {
            recordOpt = refundRecordRepository.findByMerchantIdAndMerchantRefundNo(merchantId, merchantRefundNo);
        } else {
            throw new BusinessException(400, "请提供网关退款号或商户退款单号");
        }
        
        if (recordOpt.isEmpty()) {
            throw new BusinessException(404, "退款记录不存在");
        }
        
        RefundRecord record = recordOpt.get();
        
        RefundResponse response = new RefundResponse();
        response.setGatewayRefundId(record.getRefundId());
        response.setChannelRefundNo(record.getChannelRefundNo());
        response.setStatus(record.getStatus());
        response.setAmount(record.getAmount());
        response.setCreatedAt(record.getCreatedAt());
        
        return response;
    }
    
    private PaymentOrder findOrder(RefundRequest request) {
        if (request.getGatewayOrderId() != null && !request.getGatewayOrderId().isEmpty()) {
            return paymentOrderRepository.findByOrderId(request.getGatewayOrderId())
                    .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        }
        
        if (request.getMerchantOrderNo() != null && !request.getMerchantOrderNo().isEmpty()) {
            return paymentOrderRepository.findByMerchantIdAndMerchantOrderNo(request.getMerchantId(), request.getMerchantOrderNo())
                    .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        }
        
        throw new BusinessException(400, "请提供网关订单号或商户订单号");
    }
    
    private String generateRefundId() {
        return "REF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
