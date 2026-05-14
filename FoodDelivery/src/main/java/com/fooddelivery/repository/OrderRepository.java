package com.fooddelivery.repository;

import com.fooddelivery.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    Optional<Order> findByOrderId(String orderId);
    List<Order> findByUserId(String userId);
    List<Order> findByRestaurantId(String restaurantId);
    List<Order> findByOrderStatus(String status);
    List<Order> findByOrderUrgency(String urgency);
    List<Order> findByUserIdOrderByOrderTimeDesc(String userId);
    List<Order> findByRestaurantIdOrderByOrderTimeDesc(String restaurantId);
    boolean existsByOrderId(String orderId);
}
