package com.orderflow.repository;

import com.orderflow.entity.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, String>, JpaSpecificationExecutor<OrderStatusLog> {

    List<OrderStatusLog> findByOrderIdOrderByChangedAtDesc(String orderId);
}
