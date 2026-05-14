package com.flightmgmt.search.service;

import com.flightmgmt.common.model.Flight;
import com.flightmgmt.common.util.DataStore;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class SearchService {
    public List<Flight> searchFlights(String departure, String destination, LocalDate date) {
        return DataStore.getFlights().values().stream()
            .filter(f -> f.getDeparture() != null && f.getDeparture().equalsIgnoreCase(departure))
            .filter(f -> f.getDestination() != null && f.getDestination().equalsIgnoreCase(destination))
            .filter(f -> f.getFlightDeparture() != null && 
                    f.getFlightDeparture().toLocalDate().equals(date))
            .filter(f -> "scheduled".equalsIgnoreCase(f.getFlightStatus()) || 
                    "on_time".equalsIgnoreCase(f.getFlightStatus()))
            .collect(Collectors.toList());
    }

    public List<Flight> searchFlightsByRoute(String route) {
        return DataStore.getFlights().values().stream()
            .filter(f -> f.getFlightRoute() != null && f.getFlightRoute().contains(route))
            .collect(Collectors.toList());
    }

    public List<Flight> searchFlightsByStatus(String status) {
        return DataStore.getFlights().values().stream()
            .filter(f -> f.getFlightStatus() != null && f.getFlightStatus().equalsIgnoreCase(status))
            .collect(Collectors.toList());
    }

    public List<Flight> searchFlightsByNumber(String flightNumber) {
        return DataStore.getFlights().values().stream()
            .filter(f -> f.getFlightNumber() != null && f.getFlightNumber().equalsIgnoreCase(flightNumber))
            .collect(Collectors.toList());
    }

    public List<Flight> filterByPriceRange(List<Flight> flights, double minPrice, double maxPrice) {
        return flights.stream()
            .filter(f -> f.getFlightPrice() >= minPrice && f.getFlightPrice() <= maxPrice)
            .collect(Collectors.toList());
    }

    public List<Flight> filterByAvailableSeats(List<Flight> flights, int minSeats) {
        return flights.stream()
            .filter(f -> f.getFlightAvailable() >= minSeats)
            .collect(Collectors.toList());
    }
}
