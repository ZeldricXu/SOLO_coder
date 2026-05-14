package com.homeservice.service;

import com.homeservice.entity.ServiceHistory;
import com.homeservice.repository.ServiceHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ServiceHistoryService {

    @Autowired
    private ServiceHistoryRepository serviceHistoryRepository;

    private final AtomicLong historyCounter = new AtomicLong(0);

    public ServiceHistory recordHistory(String historyType, String action, String description,
                                         String bookingId, String staffId, String customerId) {
        String historyId = "history_" + String.format("%03d", historyCounter.incrementAndGet());
        ServiceHistory history = new ServiceHistory(historyId, historyType, action);
        history.setDescription(description);
        history.setBookingId(bookingId);
        history.setStaffId(staffId);
        history.setCustomerId(customerId);
        return serviceHistoryRepository.save(history);
    }

    public ServiceHistory recordBookingHistory(String action, String description,
                                               String bookingId, String staffId, String customerId) {
        return recordHistory("BOOKING", action, description, bookingId, staffId, customerId);
    }

    public ServiceHistory recordReviewHistory(String action, String description,
                                              String bookingId, String staffId, String customerId) {
        return recordHistory("REVIEW", action, description, bookingId, staffId, customerId);
    }

    public ServiceHistory recordSettlementHistory(String action, String description,
                                                  String bookingId, String staffId, String customerId) {
        return recordHistory("SETTLEMENT", action, description, bookingId, staffId, customerId);
    }

    public List<ServiceHistory> getAllHistory() {
        return serviceHistoryRepository.findAll();
    }

    public List<ServiceHistory> getHistoryByBookingId(String bookingId) {
        return serviceHistoryRepository.findByBookingId(bookingId);
    }

    public List<ServiceHistory> getHistoryByStaffId(String staffId) {
        return serviceHistoryRepository.findByStaffId(staffId);
    }

    public List<ServiceHistory> getHistoryByCustomerId(String customerId) {
        return serviceHistoryRepository.findByCustomerId(customerId);
    }

    public List<ServiceHistory> getHistoryByType(String historyType) {
        return serviceHistoryRepository.findByHistoryType(historyType);
    }
}
