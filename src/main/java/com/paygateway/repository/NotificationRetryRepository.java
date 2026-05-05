package com.paygateway.repository;

import com.paygateway.entity.NotificationRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRetryRepository extends JpaRepository<NotificationRetry, Long> {
    
    Optional<NotificationRetry> findByRetryId(String retryId);
    
    Optional<NotificationRetry> findByOrderId(String orderId);
    
    List<NotificationRetry> findByMerchantId(String merchantId);
    
    List<NotificationRetry> findByStatus(String status);
    
    @Query("SELECT n FROM NotificationRetry n WHERE n.status = :status AND n.nextRetryAt <= :now ORDER BY n.nextRetryAt ASC")
    List<NotificationRetry> findPendingRetries(@Param("status") String status, @Param("now") LocalDateTime now);
    
    @Query("SELECT n FROM NotificationRetry n WHERE n.status = :failedStatus AND n.retryCount >= n.maxRetryCount")
    List<NotificationRetry> findFailedRetries(@Param("failedStatus") String failedStatus);
    
    boolean existsByOrderIdAndStatus(String orderId, String status);
}
