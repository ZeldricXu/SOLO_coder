package com.flightmgmt.api.dto;

public class BookingRequest {
    private String flightId;
    private String passengerName;
    private String passengerIdNumber;
    private String paymentMethod;
    private int seats;

    public BookingRequest() {}

    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPassengerIdNumber() { return passengerIdNumber; }
    public void setPassengerIdNumber(String passengerIdNumber) { this.passengerIdNumber = passengerIdNumber; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public int getSeats() { return seats; }
    public void setSeats(int seats) { this.seats = seats; }
}
