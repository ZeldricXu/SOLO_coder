package com.paycenter.service;

import com.paycenter.dto.RefundRequest;
import com.paycenter.dto.RefundResponse;
import com.paycenter.entity.Refund;

import java.util.List;
import java.util.Optional;

public interface RefundService {
    RefundResponse createRefund(RefundRequest request);
    Refund executeRefund(String refundId, boolean success);
    Optional<Refund> getRefundById(String refundId);
    List<Refund> getRefundsByTransaction(String transactionId);
}
