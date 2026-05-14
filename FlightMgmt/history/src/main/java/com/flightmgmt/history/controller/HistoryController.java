package com.flightmgmt.history.controller;

import com.flightmgmt.common.model.*;
import com.flightmgmt.history.service.HistoryService;

import java.time.LocalDateTime;
import java.util.List;

public class HistoryController {
    private HistoryService historyService = new HistoryService();

    public List<FlightStatus> getStatusHistory(String flightId) {
        return historyService.getStatusHistory(flightId);
    }

    public List<ChangeRecord> getChangeHistory(String bookingId) {
        return historyService.getChangeHistory(bookingId);
    }

    public List<Booking> getBookingHistory(String passengerId) {
        return historyService.getBookingHistory(passengerId);
    }

    public List<FlightStatus> getStatusHistoryByTimeRange(String flightId, LocalDateTime start, LocalDateTime end) {
        return historyService.getStatusHistoryByTimeRange(flightId, start, end);
    }

    public List<ChangeRecord> getChangeHistoryByTimeRange(LocalDateTime start, LocalDateTime end) {
        return historyService.getChangeHistoryByTimeRange(start, end);
    }

    public List<FlightStatus> getAllStatusHistory() {
        return historyService.getAllStatusHistory();
    }

    public List<ChangeRecord> getAllChangeHistory() {
        return historyService.getAllChangeHistory();
    }

    public List<Booking> getAllBookingHistory() {
        return historyService.getAllBookingHistory();
    }
}
