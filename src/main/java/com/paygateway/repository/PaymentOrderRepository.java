package com.paygateway.repository;

import com.paygateway.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    
    Optional<PaymentOrder> findByOrderId(String orderId);
    
    Optional<PaymentOrder> findByMerchantIdAndMerchantOrderNo(String merchantId, String merchantOrderNo);
    
    Optional<PaymentOrder> findByChannelOrderNo(String channelOrderNo);
    
    List<PaymentOrder> findByStatus(String status);
    
    @Query("SELECT COUNT(o) FROM PaymentOrder o WHERE o.createdAt BETWEEN :startTime AND :endTime")
    Long countByCreatedAtBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    
    @Query("SELECT SUM(o.amount) FROM PaymentOrder o WHERE o.createdAt BETWEEN :startTime AND :endTime AND o.status = :status")
    java.math.BigDecimal sumAmountByCreatedAtBetweenAndStatus(@Param("startTime") LocalDateTime startTime, 
                                                               @Param("endTime") LocalDateTime endTime, 
                                                               @Param("status") String status);
    
    @Query("SELECT o.channel, COUNT(o), SUM(o.amount) FROM PaymentOrder o WHERE o.createdAt BETWEEN :startTime AND :endTime GROUP BY o.channel")
    List<Object[]> countAndSumByChannelBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
