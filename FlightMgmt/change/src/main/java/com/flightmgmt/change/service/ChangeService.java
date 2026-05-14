package com.flightmgmt.change.service;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;
import com.flightmgmt.common.util.ConfigManager;
import com.flightmgmt.flight.service.FlightService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class ChangeService {
    private FlightService flightService = new FlightService();
    private ConfigManager configManager = ConfigManager.getInstance();

    public ChangeRecord processRefund(String bookingId, String reason) {
        Booking booking = DataStore.getBooking(bookingId);
        if (booking == null) {
            return null;
        }

        if (!"confirmed".equalsIgnoreCase(booking.getBookingStatus())) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("invalid_status");
            return failed;
        }

        Flight flight = DataStore.getFlight(booking.getFlightId());
        if (flight == null) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("flight_not_found");
            return failed;
        }

        double refundAmount = calculateRefund(booking, flight);

        booking.setBookingStatus("refunded");

        flightService.updateAvailableSeats(booking.getFlightId(), booking.getBookingSeats());

        ChangeRecord record = new ChangeRecord();
        record.setChangeId(IdGenerator.generateChangeId());
        record.setBookingId(bookingId);
        record.setChangeType("refund");
        record.setChangeReason(reason);
        record.setChangeAmount(refundAmount);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());

        DataStore.addChangeRecord(record);

        Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
        if (passenger != null) {
            System.out.println("退票确认: 乘客 " + passenger.getPassengerName() + 
                " 预订 " + bookingId + " 已退票，退款金额: " + refundAmount +
                " (航班类型: " + (flight.getFlightType() != null ? flight.getFlightType() : "domestic") + ")");
        }

        return record;
    }

    public ChangeRecord processRebooking(String bookingId, String newFlightId, String reason) {
        if (!configManager.isRebookAllowed()) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("rebook_not_allowed");
            return failed;
        }

        Booking booking = DataStore.getBooking(bookingId);
        if (booking == null) {
            return null;
        }

        if (!"confirmed".equalsIgnoreCase(booking.getBookingStatus())) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("invalid_status");
            return failed;
        }

        Flight newFlight = DataStore.getFlight(newFlightId);
        if (newFlight == null) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("flight_not_found");
            return failed;
        }

        if (newFlight.getFlightAvailable() < booking.getBookingSeats()) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("seats_insufficient");
            return failed;
        }

        Flight oldFlight = DataStore.getFlight(booking.getFlightId());
        if (oldFlight == null) {
            ChangeRecord failed = new ChangeRecord();
            failed.setChangeStatus("old_flight_not_found");
            return failed;
        }

        double priceDifference = calculateRebookingDifference(booking, oldFlight, newFlight);

        flightService.updateAvailableSeats(booking.getFlightId(), booking.getBookingSeats());
        flightService.updateAvailableSeats(newFlightId, -booking.getBookingSeats());

        booking.setFlightId(newFlightId);
        booking.setBookingAmount(newFlight.getFlightPrice() * booking.getBookingSeats());

        ChangeRecord record = new ChangeRecord();
        record.setChangeId(IdGenerator.generateChangeId());
        record.setBookingId(bookingId);
        record.setChangeType("rebook");
        record.setChangeReason(reason);
        record.setChangeAmount(priceDifference);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());

        DataStore.addChangeRecord(record);

        Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
        if (passenger != null) {
            System.out.println("改签确认: 乘客 " + passenger.getPassengerName() + 
                " 预订 " + bookingId + " 已改签至航班 " + newFlightId + 
                "，差价: " + priceDifference);
        }

        return record;
    }

    private double calculateRefund(Booking booking, Flight flight) {
        double originalAmount = booking.getBookingAmount();
        String flightType = flight.getFlightType() != null ? flight.getFlightType() : "domestic";
        
        long hoursBeforeDeparture = 0;
        if (flight.getFlightDeparture() != null) {
            hoursBeforeDeparture = ChronoUnit.HOURS.between(
                LocalDateTime.now(), 
                flight.getFlightDeparture()
            );
        }

        double feeRate;
        int freeCancelHours = configManager.getFreeCancelHours();
        int lastMinuteHours = configManager.getLastMinuteHours();

        if (hoursBeforeDeparture >= freeCancelHours) {
            feeRate = 0;
        } else if (hoursBeforeDeparture <= lastMinuteHours) {
            feeRate = configManager.getLastMinuteFeeRate();
        } else {
            feeRate = configManager.getRefundFeeRate(flightType);
        }

        double fee = originalAmount * feeRate;
        double refundAmount = originalAmount - fee;

        if (refundAmount < 0) {
            refundAmount = 0;
        }

        System.out.println("退票计算: 原金额=" + originalAmount + 
            ", 航班类型=" + flightType + 
            ", 费率=" + feeRate + 
            ", 手续费=" + fee + 
            ", 退款=" + refundAmount);

        return refundAmount;
    }

    private double calculateRebookingDifference(Booking booking, Flight oldFlight, Flight newFlight) {
        String oldFlightType = oldFlight.getFlightType() != null ? oldFlight.getFlightType() : "domestic";
        String newFlightType = newFlight.getFlightType() != null ? newFlight.getFlightType() : "domestic";
        
        double oldPrice = oldFlight.getFlightPrice() * booking.getBookingSeats();
        double newPrice = newFlight.getFlightPrice() * booking.getBookingSeats();
        
        double baseDifference = newPrice - oldPrice;
        
        double rebookFee = configManager.getRebookFeeRate(newFlightType) * Math.max(oldPrice, newPrice);
        
        double totalDifference = baseDifference + rebookFee;

        System.out.println("改签计算: 原价=" + oldPrice + 
            ", 新价=" + newPrice + 
            ", 基础差价=" + baseDifference + 
            ", 改签费=" + rebookFee + 
            ", 总差价=" + totalDifference);

        return totalDifference;
    }

    public double getRefundFeeRate(String flightType) {
        return configManager.getRefundFeeRate(flightType);
    }

    public double getRebookFeeRate(String flightType) {
        return configManager.getRebookFeeRate(flightType);
    }

    public int getFreeCancelHours() {
        return configManager.getFreeCancelHours();
    }

    public List<ChangeRecord> getChangeRecordsByBooking(String bookingId) {
        return DataStore.getChangeHistory().stream()
            .filter(r -> r.getBookingId() != null && r.getBookingId().equals(bookingId))
            .collect(java.util.stream.Collectors.toList());
    }

    public List<ChangeRecord> getAllChangeRecords() {
        return DataStore.getChangeRecords().values().stream()
            .collect(java.util.stream.Collectors.toList());
    }
}
