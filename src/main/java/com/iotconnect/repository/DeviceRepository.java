package com.iotconnect.repository;

import com.iotconnect.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByDeviceId(String deviceId);

    Optional<Device> findByDeviceIdAndAuthToken(String deviceId, String authToken);

    List<Device> findByConnectionStatus(String connectionStatus);

    List<Device> findByDeviceType(String deviceType);

    List<Device> findByDeviceGroup(String deviceGroup);

    @Query("SELECT COUNT(d) FROM Device d WHERE d.connectionStatus = :status")
    long countByConnectionStatus(@Param("status") String status);

    @Query("SELECT COUNT(d) FROM Device d WHERE d.deviceType = :deviceType")
    long countByDeviceType(@Param("deviceType") String deviceType);

    @Query("SELECT d FROM Device d WHERE d.lastActive < :time")
    List<Device> findInactiveDevices(@Param("time") LocalDateTime time);

    @Query("SELECT DISTINCT d.deviceGroup FROM Device d WHERE d.deviceGroup IS NOT NULL")
    List<String> findAllDeviceGroups();

    boolean existsByDeviceId(String deviceId);
}
