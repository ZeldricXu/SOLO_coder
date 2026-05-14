package com.flightmgmt.analysis.service;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class AnalysisService {
    public FlightStatistics getMonthlyStatistics(String month) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
        LocalDate targetDate = LocalDate.parse(month + "-01");
        String targetMonth = targetDate.format(formatter);

        int flightCount = 0;
        int bookingCount = 0;
        double totalAmount = 0;

        List<Flight> flights = DataStore.getFlights().values().stream()
            .filter(f -> f.getCreatedAt() != null && 
                    f.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM")).equals(targetMonth))
            .collect(Collectors.toList());
        flightCount = flights.size();

        List<Booking> bookings = DataStore.getBookings().values().stream()
            .filter(b -> b.getCreatedAt() != null && 
                    b.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM")).equals(targetMonth))
            .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
            .collect(Collectors.toList());
        bookingCount = bookings.size();
        totalAmount = bookings.stream().mapToDouble(Booking::getBookingAmount).sum();

        FlightStatistics statistics = new FlightStatistics();
        statistics.setStatId(IdGenerator.generateStatId());
        statistics.setStatMonth(targetMonth);
        statistics.setFlightCount(flightCount);
        statistics.setBookingCount(bookingCount);
        statistics.setTotalAmount(totalAmount);

        return statistics;
    }

    public Map<String, Integer> getRouteAnalysis() {
        Map<String, Integer> routeStats = new HashMap<>();
        for (Flight flight : DataStore.getFlights().values()) {
            String route = flight.getFlightRoute();
            if (route != null) {
                int count = routeStats.getOrDefault(route, 0);
                routeStats.put(route, count + 1);
            }
        }
        return routeStats;
    }

    public Map<String, Long> getStatusDistribution() {
        return DataStore.getFlights().values().stream()
            .filter(f -> f.getFlightStatus() != null)
            .collect(Collectors.groupingBy(
                Flight::getFlightStatus,
                Collectors.counting()
            ));
    }

    public double getAverageOccupancyRate() {
        List<Flight> flights = DataStore.getFlights().values().stream()
            .filter(f -> f.getFlightSeats() > 0)
            .collect(Collectors.toList());
        
        if (flights.isEmpty()) {
            return 0;
        }

        double totalRate = flights.stream()
            .mapToDouble(f -> (double) (f.getFlightSeats() - f.getFlightAvailable()) / f.getFlightSeats())
            .sum();
        
        return totalRate / flights.size() * 100;
    }

    public List<String> getPopularRoutes(int limit) {
        Map<String, Integer> routeStats = getRouteAnalysis();
        return routeStats.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
