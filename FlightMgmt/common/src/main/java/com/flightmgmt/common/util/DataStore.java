package com.flightmgmt.common.util;

import com.flightmgmt.common.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore {
    private static Map<String, Flight> flights = new ConcurrentHashMap<>();
    private static Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private static Map<String, Passenger> passengers = new ConcurrentHashMap<>();
    private static Map<String, FlightStatus> flightStatuses = new ConcurrentHashMap<>();
    private static Map<String, ChangeRecord> changeRecords = new ConcurrentHashMap<>();
    private static Map<String, FlightStatistics> statistics = new ConcurrentHashMap<>();
    private static List<FlightStatus> statusHistory = new ArrayList<>();
    private static List<ChangeRecord> changeHistory = new ArrayList<>();
    private static List<Booking> bookingHistory = new ArrayList<>();

    public static Map<String, Flight> getFlights() { return flights; }
    public static Map<String, Booking> getBookings() { return bookings; }
    public static Map<String, Passenger> getPassengers() { return passengers; }
    public static Map<String, FlightStatus> getFlightStatuses() { return flightStatuses; }
    public static Map<String, ChangeRecord> getChangeRecords() { return changeRecords; }
    public static Map<String, FlightStatistics> getStatistics() { return statistics; }
    public static List<FlightStatus> getStatusHistory() { return statusHistory; }
    public static List<ChangeRecord> getChangeHistory() { return changeHistory; }
    public static List<Booking> getBookingHistory() { return bookingHistory; }

    public static void addFlight(Flight flight) {
        flights.put(flight.getFlightId(), flight);
    }

    public static Flight getFlight(String flightId) {
        return flights.get(flightId);
    }

    public static void addBooking(Booking booking) {
        bookings.put(booking.getBookingId(), booking);
        bookingHistory.add(booking);
    }

    public static Booking getBooking(String bookingId) {
        return bookings.get(bookingId);
    }

    public static void addPassenger(Passenger passenger) {
        passengers.put(passenger.getPassengerId(), passenger);
    }

    public static Passenger getPassenger(String passengerId) {
        return passengers.get(passengerId);
    }

    public static void addFlightStatus(FlightStatus status) {
        flightStatuses.put(status.getStatusId(), status);
        statusHistory.add(status);
    }

    public static void addChangeRecord(ChangeRecord record) {
        changeRecords.put(record.getChangeId(), record);
        changeHistory.add(record);
    }

    public static void addStatistics(FlightStatistics stat) {
        statistics.put(stat.getStatId(), stat);
    }
}
