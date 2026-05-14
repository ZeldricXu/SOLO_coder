package com.logistics.repository;

import com.logistics.entity.DeliveryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryTypeRepository extends JpaRepository<DeliveryType, String> {

    Optional<DeliveryType> findByTypeCode(String typeCode);

    List<DeliveryType> findByIsActiveTrue();

    List<DeliveryType> findByIsActiveTrueOrderByPriorityAsc();

    Optional<DeliveryType> findByTypeCodeAndIsActiveTrue(String typeCode);
}
