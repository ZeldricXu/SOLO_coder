package com.deviceops.repository;

import com.deviceops.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceRepository extends JpaRepository<Device, String> {

    List<Device> findByDeviceType(String deviceType);

    List<Device> findByDeviceStatus(String deviceStatus);

    long countByDeviceStatus(String deviceStatus);
}
