package com.meeting.repository;

import com.meeting.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByRoomId(String roomId);

    List<Device> findByDeviceStatus(String deviceStatus);

    List<Device> findByRoomIdAndDeviceStatus(String roomId, String deviceStatus);

    List<Device> findByDeviceType(String deviceType);

    @Query("SELECT d FROM Device d WHERE d.roomId = :roomId AND d.deviceStatus = 'available'")
    List<Device> findAvailableDevicesByRoomId(@Param("roomId") String roomId);

    @Query("SELECT COUNT(d) FROM Device d WHERE d.roomId = :roomId AND d.deviceStatus = 'available'")
    long countAvailableDevicesByRoomId(@Param("roomId") String roomId);

    @Query("SELECT d FROM Device d WHERE d.deviceType = :type AND d.deviceStatus = 'available'")
    List<Device> findAvailableDevicesByType(@Param("type") String type);

    boolean existsByDeviceId(String deviceId);
}
