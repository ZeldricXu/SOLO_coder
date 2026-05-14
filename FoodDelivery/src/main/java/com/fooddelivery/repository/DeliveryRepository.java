package com.fooddelivery.repository;

import com.fooddelivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, String> {
    Optional<Delivery> findByDeliveryId(String deliveryId);
    Optional<Delivery> findByOrderId(String orderId);
    List<Delivery> findByRiderId(String riderId);
    List<Delivery> findByDeliveryStatus(String status);
    List<Delivery> findByRiderIdOrderByCreatedAtDesc(String riderId);
    boolean existsByOrderId(String orderId);
    boolean existsByDeliveryId(String deliveryId);
}
