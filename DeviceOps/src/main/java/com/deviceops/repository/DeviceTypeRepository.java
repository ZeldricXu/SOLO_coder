package com.deviceops.repository;

import com.deviceops.entity.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceTypeRepository extends JpaRepository<DeviceType, String> {

    Optional<DeviceType> findByTypeCode(String typeCode);

    boolean existsByTypeCode(String typeCode);
}
