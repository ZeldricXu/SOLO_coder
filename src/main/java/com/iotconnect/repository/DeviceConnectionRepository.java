package com.iotconnect.repository;

import com.iotconnect.entity.DeviceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceConnectionRepository extends JpaRepository<DeviceConnection, String> {

    Optional<DeviceConnection> findByDeviceId(String deviceId);

    Optional<DeviceConnection> findTopByDeviceIdOrderByConnectionTimeDesc(String deviceId);

    List<DeviceConnection> findByConnectionStatus(String connectionStatus);

    @Query("SELECT dc FROM DeviceConnection dc WHERE dc.lastHeartbeat < :time AND dc.connectionStatus = 'connected'")
    List<DeviceConnection> findConnectionsWithExpiredHeartbeat(@Param("time") LocalDateTime time);

    @Query("SELECT COUNT(dc) FROM DeviceConnection dc WHERE dc.connectionStatus = :status")
    long countByConnectionStatus(@Param("status") String status);

    @Query("SELECT dc FROM DeviceConnection dc WHERE dc.deviceId = :deviceId ORDER BY dc.connectionTime DESC")
    List<DeviceConnection> findConnectionHistoryByDeviceId(@Param("deviceId") String deviceId);

    boolean existsByDeviceIdAndConnectionStatus(String deviceId, String connectionStatus);
}
