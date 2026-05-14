package com.paycenter.service;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.Settlement;
import com.paycenter.entity.Transaction;
import com.paycenter.entity.TransactionStat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface QueryService {
    List<Transaction> queryTransactions(String merchantId, LocalDateTime start, LocalDateTime end);
    List<Settlement> querySettlements(SettlementQueryRequest request);
    List<TransactionStat> queryStats(String merchantId, LocalDate start, LocalDate end);
    Map<String, Object> getTransactionDetail(String transactionId);
    Map<String, Object> getAccountSummary(String merchantId);
}
