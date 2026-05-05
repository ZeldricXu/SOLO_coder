package com.orderflow.repository;

import com.orderflow.entity.Refund;
import com.orderflow.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, String>, JpaSpecificationExecutor<Refund> {

    List<Refund> findByOrderId(String orderId);

    List<Refund> findByStatus(RefundStatus status);

    Optional<Refund> findFirstByOrderIdOrderByCreatedAtDesc(String orderId);
}
