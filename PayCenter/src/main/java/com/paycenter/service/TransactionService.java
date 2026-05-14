package com.paycenter.service;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.TransactionStatus;

import java.util.List;
import java.util.Optional;

public interface TransactionService {
    PaymentResponse createPayment(PaymentRequest request);
    Transaction confirmPayment(String transactionId, boolean success, String notifyData);
    Optional<Transaction> getTransactionById(String transactionId);
    Optional<Transaction> getTransactionByOrderNo(String orderNo);
    List<Transaction> getTransactionsByMerchant(String merchantId, java.time.LocalDateTime start, java.time.LocalDateTime end);
    Transaction updateTransactionStatus(String transactionId, TransactionStatus status);
}
