package com.fooddelivery.repository;

import com.fooddelivery.entity.Notify;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotifyRepository extends JpaRepository<Notify, String> {
    List<Notify> findByOrderIdOrderByNotifyTimeDesc(String orderId);
    List<Notify> findByOrderId(String orderId);
}
