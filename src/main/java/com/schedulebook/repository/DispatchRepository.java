package com.schedulebook.repository;

import com.schedulebook.model.Dispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DispatchRepository extends JpaRepository<Dispatch, Long> {
    
    Optional<Dispatch> findByDispatchId(String dispatchId);
    
    Optional<Dispatch> findByBookingIdAndDispatchStatus(String bookingId, String dispatchStatus);
    
    List<Dispatch> findByBookingId(String bookingId);
    
    List<Dispatch> findByResourceId(String resourceId);
    
    List<Dispatch> findByDispatchStatus(String dispatchStatus);
    
    boolean existsByBookingId(String bookingId);
}
