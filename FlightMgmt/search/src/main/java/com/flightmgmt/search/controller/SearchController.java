package com.flightmgmt.search.controller;

import com.flightmgmt.common.model.Flight;
import com.flightmgmt.search.service.SearchService;

import java.time.LocalDate;
import java.util.List;

public class SearchController {
    private SearchService searchService = new SearchService();

    public List<Flight> searchFlights(String departure, String destination, LocalDate date) {
        return searchService.searchFlights(departure, destination, date);
    }

    public List<Flight> searchFlightsByRoute(String route) {
        return searchService.searchFlightsByRoute(route);
    }

    public List<Flight> searchFlightsByStatus(String status) {
        return searchService.searchFlightsByStatus(status);
    }

    public List<Flight> searchFlightsByNumber(String flightNumber) {
        return searchService.searchFlightsByNumber(flightNumber);
    }
}
