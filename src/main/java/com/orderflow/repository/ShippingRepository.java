package com.orderflow.repository;

import com.orderflow.entity.Shipping;
import com.orderflow.enums.ShippingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingRepository extends JpaRepository<Shipping, String>, JpaSpecificationExecutor<Shipping> {

    Optional<Shipping> findByOrderId(String orderId);

    List<Shipping> findByStatus(ShippingStatus status);
}
