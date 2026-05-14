package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByTableId(String tableId);
    List<Order> findByOrderStatus(String orderStatus);
    List<Order> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<Order> findByOrderStatusAndCreatedAtBetween(String orderStatus, LocalDateTime startTime, LocalDateTime endTime);
}
