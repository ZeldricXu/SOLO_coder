package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class Booking {
    private String bookingId;
    private String flightId;
    private String passengerId;
    private int bookingSeats;
    private double bookingAmount;
    private String bookingStatus;
    private String paymentMethod;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;

    public Booking() {}

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getPassengerId() { return passengerId; }
    public void setPassengerId(String passengerId) { this.passengerId = passengerId; }
    public int getBookingSeats() { return bookingSeats; }
    public void setBookingSeats(int bookingSeats) { this.bookingSeats = bookingSeats; }
    public double getBookingAmount() { return bookingAmount; }
    public void setBookingAmount(double bookingAmount) { this.bookingAmount = bookingAmount; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}
