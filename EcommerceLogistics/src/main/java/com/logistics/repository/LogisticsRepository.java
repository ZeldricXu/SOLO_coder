package com.logistics.repository;

import com.logistics.entity.Logistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface LogisticsRepository extends JpaRepository<Logistics, String> {

    Optional<Logistics> findByLogisticsNumber(String logisticsNumber);

    Optional<Logistics> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);
}
