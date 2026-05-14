package com.flightmgmt.change.controller;

import com.flightmgmt.change.service.ChangeService;
import com.flightmgmt.common.model.ChangeRecord;

import java.util.List;

public class ChangeController {
    private ChangeService changeService = new ChangeService();

    public ChangeRecord processRefund(String bookingId, String reason) {
        return changeService.processRefund(bookingId, reason);
    }

    public ChangeRecord processRebooking(String bookingId, String newFlightId, String reason) {
        return changeService.processRebooking(bookingId, newFlightId, reason);
    }

    public List<ChangeRecord> getChangeRecordsByBooking(String bookingId) {
        return changeService.getChangeRecordsByBooking(bookingId);
    }

    public List<ChangeRecord> getAllChangeRecords() {
        return changeService.getAllChangeRecords();
    }
}
