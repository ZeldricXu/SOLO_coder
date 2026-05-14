package com.paycenter.repository;

import com.paycenter.entity.Transaction;
import com.paycenter.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    Optional<Transaction> findByOrderNo(String orderNo);
    List<Transaction> findByMerchantIdAndCreatedAtBetween(String merchantId, LocalDateTime start, LocalDateTime end);
    List<Transaction> findByMerchantIdAndStatusIn(String merchantId, List<TransactionStatus> statuses);
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.merchantId = :merchantId AND t.status = 'SUCCESS' AND t.createdAt BETWEEN :start AND :end")
    BigDecimal sumSuccessAmountByMerchantIdAndCreatedAtBetween(@Param("merchantId") String merchantId, 
                                                                @Param("start") LocalDateTime start, 
                                                                @Param("end") LocalDateTime end);
    
    @Query("SELECT SUM(t.refundedAmount) FROM Transaction t WHERE t.merchantId = :merchantId AND t.refundedAmount > 0 AND t.createdAt BETWEEN :start AND :end")
    BigDecimal sumRefundedAmountByMerchantIdAndCreatedAtBetween(@Param("merchantId") String merchantId,
                                                                 @Param("start") LocalDateTime start,
                                                                 @Param("end") LocalDateTime end);
    
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.merchantId = :merchantId AND t.status = 'SUCCESS' AND t.createdAt BETWEEN :start AND :end")
    Long countSuccessByMerchantIdAndCreatedAtBetween(@Param("merchantId") String merchantId,
                                                      @Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);
}
