package com.logistics.repository;

import com.logistics.entity.DeliveryTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryTaskRepository extends JpaRepository<DeliveryTask, String> {

    Optional<DeliveryTask> findByLogisticsId(String logisticsId);

    List<DeliveryTask> findByCourierId(String courierId);

    List<DeliveryTask> findByCourierIdAndTaskStatus(String courierId, String status);

    List<DeliveryTask> findByTaskStatus(String status);
}
