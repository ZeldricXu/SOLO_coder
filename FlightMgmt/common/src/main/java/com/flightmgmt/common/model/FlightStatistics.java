package com.flightmgmt.common.model;

public class FlightStatistics {
    private String statId;
    private String statMonth;
    private int flightCount;
    private int bookingCount;
    private double totalAmount;

    public FlightStatistics() {}

    public String getStatId() { return statId; }
    public void setStatId(String statId) { this.statId = statId; }
    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public int getFlightCount() { return flightCount; }
    public void setFlightCount(int flightCount) { this.flightCount = flightCount; }
    public int getBookingCount() { return bookingCount; }
    public void setBookingCount(int bookingCount) { this.bookingCount = bookingCount; }
    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }
}
