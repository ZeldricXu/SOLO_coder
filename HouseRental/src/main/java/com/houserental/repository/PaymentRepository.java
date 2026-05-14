package com.houserental.repository;

import com.houserental.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByPaymentId(String paymentId);

    List<Payment> findByContractId(String contractId);

    List<Payment> findByTenantId(String tenantId);

    List<Payment> findByPaymentStatus(String status);

    List<Payment> findByContractIdAndPaymentStatus(String contractId, String status);

    List<Payment> findByTenantIdAndPaymentStatus(String tenantId, String status);

    @Query("SELECT p FROM Payment p WHERE p.contractId = :contractId AND p.paymentPeriod = :period")
    Optional<Payment> findByContractIdAndPeriod(@Param("contractId") String contractId, @Param("period") String period);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.paymentStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(p) FROM Payment p")
    long countTotalPayments();

    @Query("SELECT SUM(p.paymentAmount) FROM Payment p WHERE p.paymentStatus = 'paid'")
    Double sumTotalPaidAmount();

    @Query("SELECT SUM(p.paymentAmount) FROM Payment p WHERE p.paymentStatus = 'paid' AND p.paidAt BETWEEN :start AND :end")
    Double sumPaidAmountByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    boolean existsByPaymentId(String paymentId);
}
