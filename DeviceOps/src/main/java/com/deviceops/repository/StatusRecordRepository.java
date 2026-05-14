package com.deviceops.repository;

import com.deviceops.entity.StatusRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StatusRecordRepository extends JpaRepository<StatusRecord, String> {

    List<StatusRecord> findByDeviceIdOrderByStatusTimeDesc(String deviceId);

    List<StatusRecord> findByDeviceIdAndStatusType(String deviceId, String statusType);

    List<StatusRecord> findTop10ByDeviceIdOrderByStatusTimeDesc(String deviceId);
}
