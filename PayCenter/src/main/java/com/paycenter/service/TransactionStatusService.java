package com.paycenter.service;

import com.paycenter.entity.TransactionStatusLog;
import com.paycenter.enums.TransactionStatus;

import java.util.List;

public interface TransactionStatusService {
    void logStatusChange(String transactionId, TransactionStatus fromStatus, TransactionStatus toStatus, String remark);
    List<TransactionStatusLog> getStatusHistory(String transactionId);
}
