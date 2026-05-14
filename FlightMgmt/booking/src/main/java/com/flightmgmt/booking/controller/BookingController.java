package com.flightmgmt.booking.controller;

import com.flightmgmt.booking.service.BookingService;
import com.flightmgmt.common.model.Booking;

import java.util.List;

public class BookingController {
    private BookingService bookingService = new BookingService();

    public Booking createBooking(String flightId, String passengerName, String passengerIdNumber, 
                                  String paymentMethod, int seats) {
        return bookingService.createBooking(flightId, passengerName, passengerIdNumber, paymentMethod, seats);
    }

    public Booking getBooking(String bookingId) {
        return bookingService.getBooking(bookingId);
    }

    public List<Booking> getAllBookings() {
        return bookingService.getAllBookings();
    }

    public List<Booking> getBookingsByFlight(String flightId) {
        return bookingService.getBookingsByFlight(flightId);
    }

    public List<Booking> getBookingsByPassenger(String passengerId) {
        return bookingService.getBookingsByPassenger(passengerId);
    }
}
