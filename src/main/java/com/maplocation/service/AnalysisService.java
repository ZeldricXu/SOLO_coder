package com.maplocation.service;

import com.maplocation.model.LocationQueryCount;
import com.maplocation.model.LocationStatistics;
import com.maplocation.repository.LocationQueryCountRepository;
import com.maplocation.repository.LocationStatisticsRepository;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final LocationStatisticsRepository statisticsRepository;
    private final LocationQueryCountRepository queryCountRepository;

    private final AtomicInteger dailyQueryCount = new AtomicInteger(0);
    private final AtomicInteger dailyRouteCount = new AtomicInteger(0);
    private final AtomicReference<Double> totalDistance = new AtomicReference<>(0.0);
    private final AtomicInteger distanceCount = new AtomicInteger(0);

    public void incrementQueryCount() {
        dailyQueryCount.incrementAndGet();
    }

    public void incrementRouteCount() {
        dailyRouteCount.incrementAndGet();
    }

    public void updateAvgDistance(double distance) {
        totalDistance.updateAndGet(v -> v + distance);
        distanceCount.incrementAndGet();
    }

    public void updateLocationHotness(List<String> locationIds) {
        for (String locationId : locationIds) {
            Optional<LocationQueryCount> existing = queryCountRepository.findById(locationId);
            if (existing.isPresent()) {
                LocationQueryCount count = existing.get();
                count.setQueryCount(count.getQueryCount() + 1);
                queryCountRepository.save(count);
            } else {
                LocationQueryCount newCount = LocationQueryCount.builder()
                        .locationId(locationId)
                        .queryCount(1)
                        .build();
                queryCountRepository.save(newCount);
            }
        }
    }

    public LocationStatistics getTodayStatistics() {
        LocalDate today = LocalDate.now();
        Optional<LocationStatistics> existing = statisticsRepository.findByStatDate(today);

        int queryCount = dailyQueryCount.get();
        int routeCount = dailyRouteCount.get();
        double avgDistance = distanceCount.get() > 0
                ? totalDistance.get() / distanceCount.get()
                : 0.0;

        List<LocationQueryCount> hotLocations = queryCountRepository.findTop10ByOrderByQueryCountDesc();
        List<LocationStatistics.HotLocation> hotLocationList = new ArrayList<>();
        for (LocationQueryCount hl : hotLocations) {
            hotLocationList.add(LocationStatistics.HotLocation.builder()
                    .locationId(hl.getLocationId())
                    .queryCount(hl.getQueryCount())
                    .build());
        }

        if (existing.isPresent()) {
            LocationStatistics stats = existing.get();
            stats.setQueryCount(queryCount);
            stats.setRouteCount(routeCount);
            stats.setAvgDistance(avgDistance);
            stats.setHotLocations(hotLocationList);
            return statisticsRepository.save(stats);
        } else {
            LocationStatistics stats = LocationStatistics.builder()
                    .statId(IdGenerator.generateStatId())
                    .statDate(today)
                    .queryCount(queryCount)
                    .routeCount(routeCount)
                    .avgDistance(avgDistance)
                    .hotLocations(hotLocationList)
                    .build();
            return statisticsRepository.save(stats);
        }
    }

    public LocationStatistics getStatisticsByDate(LocalDate date) {
        return statisticsRepository.findByStatDate(date).orElse(null);
    }

    public List<LocationQueryCount> getHotLocations() {
        return queryCountRepository.findTop10ByOrderByQueryCountDesc();
    }
}
