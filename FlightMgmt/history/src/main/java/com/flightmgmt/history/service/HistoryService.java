package com.flightmgmt.history.service;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class HistoryService {
    public List<FlightStatus> getStatusHistory(String flightId) {
        return DataStore.getStatusHistory().stream()
            .filter(s -> s.getFlightId() != null && s.getFlightId().equals(flightId))
            .collect(Collectors.toList());
    }

    public List<ChangeRecord> getChangeHistory(String bookingId) {
        return DataStore.getChangeHistory().stream()
            .filter(r -> r.getBookingId() != null && r.getBookingId().equals(bookingId))
            .collect(Collectors.toList());
    }

    public List<Booking> getBookingHistory(String passengerId) {
        return DataStore.getBookingHistory().stream()
            .filter(b -> b.getPassengerId() != null && b.getPassengerId().equals(passengerId))
            .collect(Collectors.toList());
    }

    public List<FlightStatus> getStatusHistoryByTimeRange(String flightId, LocalDateTime start, LocalDateTime end) {
        return DataStore.getStatusHistory().stream()
            .filter(s -> s.getFlightId() != null && s.getFlightId().equals(flightId))
            .filter(s -> s.getStatusTime() != null && 
                    !s.getStatusTime().isBefore(start) && !s.getStatusTime().isAfter(end))
            .collect(Collectors.toList());
    }

    public List<ChangeRecord> getChangeHistoryByTimeRange(LocalDateTime start, LocalDateTime end) {
        return DataStore.getChangeHistory().stream()
            .filter(r -> r.getChangeTime() != null && 
                    !r.getChangeTime().isBefore(start) && !r.getChangeTime().isAfter(end))
            .collect(Collectors.toList());
    }

    public List<FlightStatus> getAllStatusHistory() {
        return DataStore.getStatusHistory();
    }

    public List<ChangeRecord> getAllChangeHistory() {
        return DataStore.getChangeHistory();
    }

    public List<Booking> getAllBookingHistory() {
        return DataStore.getBookingHistory();
    }
}
