package com.orderflow.repository;

import com.orderflow.entity.Payment;
import com.orderflow.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String>, JpaSpecificationExecutor<Payment> {

    List<Payment> findByOrderId(String orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    List<Payment> findByOrderIdAndStatus(String orderId, PaymentStatus status);

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(String orderId);
}
