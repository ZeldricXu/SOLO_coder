package com.flightmgmt.flight.service;

import com.flightmgmt.common.model.Flight;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class FlightService {
    public Flight createFlight(Flight flight) {
        flight.setFlightId(IdGenerator.generateFlightId());
        flight.setFlightStatus("scheduled");
        flight.setFlightAvailable(flight.getFlightSeats());
        flight.setCreatedAt(LocalDateTime.now());
        flight.setFlightRoute(flight.getDeparture() + "-" + flight.getDestination());
        DataStore.addFlight(flight);
        return flight;
    }

    public Flight updateFlight(String flightId, Flight flight) {
        Flight existing = DataStore.getFlight(flightId);
        if (existing == null) {
            return null;
        }
        existing.setFlightNumber(flight.getFlightNumber());
        existing.setDeparture(flight.getDeparture());
        existing.setDestination(flight.getDestination());
        existing.setFlightRoute(flight.getDeparture() + "-" + flight.getDestination());
        existing.setFlightDeparture(flight.getFlightDeparture());
        existing.setFlightArrival(flight.getFlightArrival());
        existing.setFlightSeats(flight.getFlightSeats());
        existing.setFlightPrice(flight.getFlightPrice());
        return existing;
    }

    public Flight getFlight(String flightId) {
        return DataStore.getFlight(flightId);
    }

    public List<Flight> getAllFlights() {
        return DataStore.getFlights().values().stream().collect(Collectors.toList());
    }

    public boolean deleteFlight(String flightId) {
        return DataStore.getFlights().remove(flightId) != null;
    }

    public Flight updateFlightStatus(String flightId, String status) {
        Flight flight = DataStore.getFlight(flightId);
        if (flight != null) {
            flight.setFlightStatus(status);
        }
        return flight;
    }

    public boolean updateAvailableSeats(String flightId, int seats) {
        Flight flight = DataStore.getFlight(flightId);
        if (flight == null) {
            return false;
        }
        int newAvailable = flight.getFlightAvailable() + seats;
        if (newAvailable < 0 || newAvailable > flight.getFlightSeats()) {
            return false;
        }
        flight.setFlightAvailable(newAvailable);
        return true;
    }
}
