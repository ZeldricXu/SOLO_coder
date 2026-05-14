package com.paycenter.service.impl;

import com.paycenter.entity.TransactionStatusLog;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.repository.TransactionStatusLogRepository;
import com.paycenter.service.TransactionStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionStatusServiceImpl implements TransactionStatusService {

    @Autowired
    private TransactionStatusLogRepository transactionStatusLogRepository;

    @Override
    @Transactional
    public void logStatusChange(String transactionId, TransactionStatus fromStatus, TransactionStatus toStatus, String remark) {
        TransactionStatusLog log = TransactionStatusLog.builder()
                .transactionId(transactionId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .remark(remark)
                .build();
        transactionStatusLogRepository.save(log);
    }

    @Override
    public List<TransactionStatusLog> getStatusHistory(String transactionId) {
        return transactionStatusLogRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId);
    }
}
