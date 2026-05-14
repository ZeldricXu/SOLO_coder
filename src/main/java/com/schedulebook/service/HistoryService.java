package com.schedulebook.service;

import com.schedulebook.model.Booking;
import com.schedulebook.model.BookingHistory;
import com.schedulebook.repository.BookingHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);
    
    @Autowired
    private BookingHistoryRepository historyRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Transactional
    public void recordHistory(Booking booking, String actionType, String actionDetail) {
        logger.info("记录预约历史，预约ID: {}, 操作类型: {}", booking.getBookingId(), actionType);
        
        BookingHistory history = new BookingHistory();
        history.setHistoryId(idGeneratorService.generateHistoryId());
        history.setBookingId(booking.getBookingId());
        history.setUserId(booking.getUserId());
        history.setResourceType(booking.getResourceType());
        history.setResourceId(booking.getResourceId());
        history.setBookingDate(booking.getBookingDate());
        history.setBookingTime(booking.getBookingTime());
        history.setFinalStatus(booking.getBookingStatus());
        history.setActionType(actionType);
        history.setActionTime(LocalDateTime.now());
        history.setActionDetail(actionDetail);
        
        historyRepository.save(history);
        logger.info("历史记录创建成功，历史ID: {}", history.getHistoryId());
    }
    
    public List<BookingHistory> getHistoryByBooking(String bookingId) {
        return historyRepository.findByBookingId(bookingId);
    }
    
    public List<BookingHistory> getHistoryByUser(String userId) {
        return historyRepository.findByUserIdOrderByActionTimeDesc(userId);
    }
    
    public List<BookingHistory> getHistoryByDate(LocalDate date) {
        return historyRepository.findByBookingDate(date);
    }
    
    public List<BookingHistory> getHistoryByDateRange(LocalDate startDate, LocalDate endDate) {
        return historyRepository.findByDateRange(startDate, endDate);
    }
    
    public List<BookingHistory> getHistoryByUserAndDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        return historyRepository.findByUserIdAndDateRange(userId, startDate, endDate);
    }
}
