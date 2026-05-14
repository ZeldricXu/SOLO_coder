package com.iotconnect.repository;

import com.iotconnect.entity.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, String> {

    List<AlertEvent> findByDeviceId(String deviceId);

    List<AlertEvent> findByStatus(String status);

    List<AlertEvent> findBySeverity(String severity);

    @Query("SELECT ae FROM AlertEvent ae WHERE ae.deviceId = :deviceId AND ae.status = 'triggered'")
    List<AlertEvent> findActiveAlertsByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT ae FROM AlertEvent ae WHERE ae.deviceId = :deviceId AND ae.ruleId = :ruleId AND ae.status = 'triggered' ORDER BY ae.triggeredAt DESC")
    List<AlertEvent> findActiveAlertsByDeviceIdAndRuleId(@Param("deviceId") String deviceId, @Param("ruleId") String ruleId);

    @Query("SELECT ae FROM AlertEvent ae WHERE ae.triggeredAt >= :startTime ORDER BY ae.triggeredAt DESC")
    List<AlertEvent> findAlertsSince(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT COUNT(ae) FROM AlertEvent ae WHERE ae.status = 'triggered'")
    long countActiveAlerts();

    @Query("SELECT COUNT(ae) FROM AlertEvent ae WHERE ae.status = 'triggered' AND ae.severity = :severity")
    long countActiveAlertsBySeverity(@Param("severity") String severity);

    @Query("SELECT ae FROM AlertEvent ae WHERE ae.deviceId = :deviceId ORDER BY ae.triggeredAt DESC")
    List<AlertEvent> findAlertHistoryByDeviceId(@Param("deviceId") String deviceId);
}
