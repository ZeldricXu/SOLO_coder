package com.maplocation.service;

import com.maplocation.dto.DistanceCalculateRequest;
import com.maplocation.dto.DistanceCalculateResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.DistanceRecord;
import com.maplocation.model.Location;
import com.maplocation.repository.DistanceRecordRepository;
import com.maplocation.util.GeoUtils;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DistanceService {

    private static final Logger logger = LoggerFactory.getLogger(DistanceService.class);

    private final DistanceRecordRepository distanceRecordRepository;
    private final LocationService locationService;

    public DistanceCalculateResponse calculateDistance(DistanceCalculateRequest request) {
        try {
            String distanceType = request.getDistanceType() != null ? request.getDistanceType() : "direct";

            double distance = calculateDistanceByType(
                    request.getFromLocation(),
                    request.getToLocation(),
                    distanceType
            );

            DistanceRecord record = DistanceRecord.builder()
                    .distanceId(IdGenerator.generateDistanceId())
                    .fromLocation(getLocationIdOrCoords(request.getFromLocation()))
                    .toLocation(getLocationIdOrCoords(request.getToLocation()))
                    .distanceType(distanceType)
                    .distanceValue(distance)
                    .distanceUnit("meter")
                    .calculatedAt(Instant.now())
                    .build();

            distanceRecordRepository.save(record);

            return DistanceCalculateResponse.builder()
                    .distanceId(record.getDistanceId())
                    .distanceValue(distance)
                    .distanceUnit("meter")
                    .distanceType(distanceType)
                    .build();

        } catch (Exception e) {
            logger.error("Distance calculation failed", e);
            throw new RuntimeException("Distance calculation failed: " + e.getMessage());
        }
    }

    private double calculateDistanceByType(Coordinates from, Coordinates to, String distanceType) {
        double directDistance = GeoUtils.calculateDistance(from, to);

        return switch (distanceType) {
            case "direct" -> directDistance;
            case "driving" -> directDistance * 1.3;
            case "walking" -> directDistance * 1.1;
            default -> directDistance;
        };
    }

    private String getLocationIdOrCoords(Coordinates coords) {
        if (coords == null) {
            return null;
        }
        return coords.getLat() + "," + coords.getLng();
    }

    public double calculateDistanceBetweenLocations(String locationId1, String locationId2) {
        Optional<Location> loc1 = locationService.getLocationById(locationId1);
        Optional<Location> loc2 = locationService.getLocationById(locationId2);

        if (loc1.isEmpty() || loc2.isEmpty()) {
            throw new RuntimeException("Location not found");
        }

        return GeoUtils.calculateDistance(
                loc1.get().getLocationCoordinates(),
                loc2.get().getLocationCoordinates()
        );
    }
}
