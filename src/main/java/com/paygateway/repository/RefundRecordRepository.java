package com.paygateway.repository;

import com.paygateway.entity.RefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRecordRepository extends JpaRepository<RefundRecord, Long> {
    
    Optional<RefundRecord> findByRefundId(String refundId);
    
    Optional<RefundRecord> findByMerchantIdAndMerchantRefundNo(String merchantId, String merchantRefundNo);
    
    List<RefundRecord> findByOrderId(String orderId);
    
    List<RefundRecord> findByStatus(String status);
}
