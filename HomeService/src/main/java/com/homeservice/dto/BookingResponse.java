package com.homeservice.dto;

public class BookingResponse {
    private String bookingId;
    private String status;

    public BookingResponse() {}

    public BookingResponse(String bookingId, String status) {
        this.bookingId = bookingId;
        this.status = status;
    }

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
