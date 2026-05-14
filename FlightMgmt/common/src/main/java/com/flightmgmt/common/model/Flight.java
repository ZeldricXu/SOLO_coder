package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class Flight {
    private String flightId;
    private String flightNumber;
    private String flightRoute;
    private String departure;
    private String destination;
    private String flightType;
    private LocalDateTime flightDeparture;
    private LocalDateTime flightArrival;
    private String flightStatus;
    private int flightSeats;
    private int flightAvailable;
    private double flightPrice;
    private LocalDateTime createdAt;

    public Flight() {}

    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
    public String getFlightRoute() { return flightRoute; }
    public void setFlightRoute(String flightRoute) { this.flightRoute = flightRoute; }
    public String getDeparture() { return departure; }
    public void setDeparture(String departure) { this.departure = departure; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getFlightType() { return flightType; }
    public void setFlightType(String flightType) { this.flightType = flightType; }
    public LocalDateTime getFlightDeparture() { return flightDeparture; }
    public void setFlightDeparture(LocalDateTime flightDeparture) { this.flightDeparture = flightDeparture; }
    public LocalDateTime getFlightArrival() { return flightArrival; }
    public void setFlightArrival(LocalDateTime flightArrival) { this.flightArrival = flightArrival; }
    public String getFlightStatus() { return flightStatus; }
    public void setFlightStatus(String flightStatus) { this.flightStatus = flightStatus; }
    public int getFlightSeats() { return flightSeats; }
    public void setFlightSeats(int flightSeats) { this.flightSeats = flightSeats; }
    public int getFlightAvailable() { return flightAvailable; }
    public void setFlightAvailable(int flightAvailable) { this.flightAvailable = flightAvailable; }
    public double getFlightPrice() { return flightPrice; }
    public void setFlightPrice(double flightPrice) { this.flightPrice = flightPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
