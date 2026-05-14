package com.paycenter.repository;

import com.paycenter.entity.TransactionStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionStatusLogRepository extends JpaRepository<TransactionStatusLog, Long> {
    List<TransactionStatusLog> findByTransactionIdOrderByCreatedAtAsc(String transactionId);
}
