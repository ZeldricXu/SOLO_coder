package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.entity.Station;
import com.logistics.service.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;

    @PostMapping("/create")
    public ApiResponse<Station> createStation(@RequestBody Station station) {
        Station created = stationService.createStation(station);
        return ApiResponse.success(created);
    }

    @GetMapping("/{stationId}")
    public ApiResponse<Station> getStationById(@PathVariable String stationId) {
        Station station = stationService.getStationById(stationId);
        return ApiResponse.success(station);
    }

    @GetMapping("/list")
    public ApiResponse<List<Station>> getAllStations() {
        List<Station> stations = stationService.getAllStations();
        return ApiResponse.success(stations);
    }

    @GetMapping("/active")
    public ApiResponse<List<Station>> getActiveStations() {
        List<Station> stations = stationService.getActiveStations();
        return ApiResponse.success(stations);
    }

    @PutMapping("/{stationId}")
    public ApiResponse<Station> updateStation(@PathVariable String stationId, @RequestBody Station stationDetails) {
        Station updated = stationService.updateStation(stationId, stationDetails);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{stationId}")
    public ApiResponse<Void> deleteStation(@PathVariable String stationId) {
        stationService.deleteStation(stationId);
        return ApiResponse.success(null);
    }
}
