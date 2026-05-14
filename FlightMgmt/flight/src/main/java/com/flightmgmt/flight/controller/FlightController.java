package com.flightmgmt.flight.controller;

import com.flightmgmt.common.model.Flight;
import com.flightmgmt.flight.service.FlightService;

import java.util.List;

public class FlightController {
    private FlightService flightService = new FlightService();

    public Flight createFlight(Flight flight) {
        return flightService.createFlight(flight);
    }

    public Flight updateFlight(String flightId, Flight flight) {
        return flightService.updateFlight(flightId, flight);
    }

    public Flight getFlight(String flightId) {
        return flightService.getFlight(flightId);
    }

    public List<Flight> getAllFlights() {
        return flightService.getAllFlights();
    }

    public boolean deleteFlight(String flightId) {
        return flightService.deleteFlight(flightId);
    }
}
