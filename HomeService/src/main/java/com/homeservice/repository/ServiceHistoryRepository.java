package com.homeservice.repository;

import com.homeservice.entity.ServiceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceHistoryRepository extends JpaRepository<ServiceHistory, Long> {
    Optional<ServiceHistory> findByHistoryId(String historyId);
    List<ServiceHistory> findByBookingId(String bookingId);
    List<ServiceHistory> findByStaffId(String staffId);
    List<ServiceHistory> findByCustomerId(String customerId);
    List<ServiceHistory> findByHistoryType(String historyType);
}
