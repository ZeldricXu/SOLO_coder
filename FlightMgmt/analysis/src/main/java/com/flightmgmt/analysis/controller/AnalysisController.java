package com.flightmgmt.analysis.controller;

import com.flightmgmt.analysis.service.AnalysisService;
import com.flightmgmt.common.model.FlightStatistics;

import java.util.List;
import java.util.Map;

public class AnalysisController {
    private AnalysisService analysisService = new AnalysisService();

    public FlightStatistics getMonthlyStatistics(String month) {
        return analysisService.getMonthlyStatistics(month);
    }

    public Map<String, Integer> getRouteAnalysis() {
        return analysisService.getRouteAnalysis();
    }

    public Map<String, Long> getStatusDistribution() {
        return analysisService.getStatusDistribution();
    }

    public double getAverageOccupancyRate() {
        return analysisService.getAverageOccupancyRate();
    }

    public List<String> getPopularRoutes(int limit) {
        return analysisService.getPopularRoutes(limit);
    }
}
