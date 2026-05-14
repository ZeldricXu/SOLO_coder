package com.hotelbooking.repository;

import com.hotelbooking.model.ServiceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceRecordRepository extends JpaRepository<ServiceRecord, String> {
    List<ServiceRecord> findByRoomId(String roomId);
    List<ServiceRecord> findByServiceStatus(String status);
    List<ServiceRecord> findByRoomIdAndServiceStatus(String roomId, String status);
}
