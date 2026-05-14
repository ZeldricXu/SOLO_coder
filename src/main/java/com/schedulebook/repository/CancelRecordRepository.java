package com.schedulebook.repository;

import com.schedulebook.model.CancelRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CancelRecordRepository extends JpaRepository<CancelRecord, Long> {
    
    Optional<CancelRecord> findByCancelId(String cancelId);
    
    List<CancelRecord> findByBookingId(String bookingId);
    
    boolean existsByBookingId(String bookingId);
}
