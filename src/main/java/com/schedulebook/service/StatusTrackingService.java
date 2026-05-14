package com.schedulebook.service;

import com.schedulebook.model.Booking;
import com.schedulebook.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class StatusTrackingService {
    
    private static final Logger logger = LoggerFactory.getLogger(StatusTrackingService.class);
    
    @Autowired
    private BookingRepository bookingRepository;
    
    @Transactional
    public void updateStatus(String bookingId, String newStatus) {
        logger.info("更新预约状态，预约ID: {}, 新状态: {}", bookingId, newStatus);
        
        Optional<Booking> optional = bookingRepository.findByBookingId(bookingId);
        
        if (optional.isPresent()) {
            Booking booking = optional.get();
            String oldStatus = booking.getBookingStatus();
            
            if (!oldStatus.equals(newStatus)) {
                logger.info("预约状态变更: {} -> {}", oldStatus, newStatus);
            }
        } else {
            logger.warn("预约不存在，无法更新状态，预约ID: {}", bookingId);
        }
    }
    
    public String getStatus(String bookingId) {
        Optional<Booking> optional = bookingRepository.findByBookingId(bookingId);
        
        if (optional.isPresent()) {
            return optional.get().getBookingStatus();
        }
        
        throw new RuntimeException("预约不存在");
    }
    
    public boolean isStatusValid(String status) {
        return status != null && (
                "pending".equals(status) ||
                "confirmed".equals(status) ||
                "cancelled".equals(status) ||
                "completed".equals(status) ||
                "rejected".equals(status)
        );
    }
}
