package com.iotconnect.repository;

import com.iotconnect.entity.ControlCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ControlCommandRepository extends JpaRepository<ControlCommand, String> {

    List<ControlCommand> findByDeviceId(String deviceId);

    List<ControlCommand> findByStatus(String status);

    @Query("SELECT cc FROM ControlCommand cc WHERE cc.deviceId = :deviceId AND cc.status = 'pending' ORDER BY cc.issuedAt ASC")
    List<ControlCommand> findPendingCommandsByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT cc FROM ControlCommand cc WHERE cc.status = 'pending' AND cc.issuedAt < :timeoutTime")
    List<ControlCommand> findPendingCommandsOlderThan(@Param("timeoutTime") LocalDateTime timeoutTime);

    @Query("SELECT cc FROM ControlCommand cc WHERE cc.deviceId = :deviceId ORDER BY cc.issuedAt DESC")
    List<ControlCommand> findCommandHistoryByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT COUNT(cc) FROM ControlCommand cc WHERE cc.status = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(cc) FROM ControlCommand cc WHERE cc.status = 'pending'")
    long countPendingCommands();
}
