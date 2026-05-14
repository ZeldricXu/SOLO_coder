package com.iotconnect.repository;

import com.iotconnect.entity.DeviceTypeBatchConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTypeBatchConfigRepository extends JpaRepository<DeviceTypeBatchConfig, Long> {

    Optional<DeviceTypeBatchConfig> findByDeviceType(String deviceType);

    Optional<DeviceTypeBatchConfig> findByDeviceTypeAndEnabledTrue(String deviceType);

    List<DeviceTypeBatchConfig> findByEnabledTrue();

    boolean existsByDeviceType(String deviceType);

    void deleteByDeviceType(String deviceType);
}
