package com.iotconnect.repository;

import com.iotconnect.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {

    List<AlertRule> findByEnabledTrue();

    List<AlertRule> findByDeviceTypeAndEnabledTrue(String deviceType);

    @Query("SELECT ar FROM AlertRule ar WHERE ar.deviceType = :deviceType AND ar.metric = :metric AND ar.enabled = true")
    List<AlertRule> findByDeviceTypeAndMetricAndEnabledTrue(@Param("deviceType") String deviceType, @Param("metric") String metric);

    List<AlertRule> findBySeverity(String severity);

    @Query("SELECT COUNT(ar) FROM AlertRule ar WHERE ar.enabled = true")
    long countEnabledRules();

    @Query("SELECT DISTINCT ar.deviceType FROM AlertRule ar WHERE ar.enabled = true")
    List<String> findAllEnabledDeviceTypes();
}
