package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class Passenger {
    private String passengerId;
    private String passengerName;
    private String passengerIdType;
    private String passengerIdNumber;
    private String passengerPhone;
    private LocalDateTime createdAt;

    public Passenger() {}

    public String getPassengerId() { return passengerId; }
    public void setPassengerId(String passengerId) { this.passengerId = passengerId; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPassengerIdType() { return passengerIdType; }
    public void setPassengerIdType(String passengerIdType) { this.passengerIdType = passengerIdType; }
    public String getPassengerIdNumber() { return passengerIdNumber; }
    public void setPassengerIdNumber(String passengerIdNumber) { this.passengerIdNumber = passengerIdNumber; }
    public String getPassengerPhone() { return passengerPhone; }
    public void setPassengerPhone(String passengerPhone) { this.passengerPhone = passengerPhone; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
