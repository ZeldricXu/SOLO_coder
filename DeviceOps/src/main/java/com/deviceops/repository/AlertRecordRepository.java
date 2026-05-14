package com.deviceops.repository;

import com.deviceops.entity.AlertRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRecordRepository extends JpaRepository<AlertRecord, String> {

    List<AlertRecord> findByDeviceIdOrderByAlertTimeDesc(String deviceId);

    List<AlertRecord> findByAlertStatus(String alertStatus);

    List<AlertRecord> findByAcknowledged(Boolean acknowledged);

    List<AlertRecord> findByAlertLevel(String alertLevel);
}
