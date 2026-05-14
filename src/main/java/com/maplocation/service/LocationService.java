package com.maplocation.service;

import com.maplocation.dto.LocationCreateRequest;
import com.maplocation.model.Location;
import com.maplocation.repository.LocationRepository;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationIndexService locationIndexService;

    public Location createLocation(LocationCreateRequest request) {
        Location location = Location.builder()
                .locationId(IdGenerator.generateLocationId())
                .locationName(request.getLocationName())
                .locationType(request.getLocationType())
                .locationAddress(request.getLocationAddress())
                .locationCoordinates(request.getLocationCoordinates())
                .locationCategory(request.getLocationCategory())
                .locationTags(request.getLocationTags())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Location saved = locationRepository.save(location);

        try {
            locationIndexService.buildIndex(saved);
            log.info("Created location with index: {}", saved.getLocationId());
        } catch (Exception e) {
            log.warn("Failed to build index for location {}: {}", saved.getLocationId(), e.getMessage());
        }

        return saved;
    }

    public Location updateLocation(String locationId, LocationCreateRequest request) {
        Optional<Location> existingOpt = locationRepository.findById(locationId);
        if (existingOpt.isEmpty()) {
            throw new RuntimeException("Location not found: " + locationId);
        }

        Location existing = existingOpt.get();
        if (request.getLocationName() != null) {
            existing.setLocationName(request.getLocationName());
        }
        if (request.getLocationType() != null) {
            existing.setLocationType(request.getLocationType());
        }
        if (request.getLocationAddress() != null) {
            existing.setLocationAddress(request.getLocationAddress());
        }
        if (request.getLocationCoordinates() != null) {
            existing.setLocationCoordinates(request.getLocationCoordinates());
        }
        if (request.getLocationCategory() != null) {
            existing.setLocationCategory(request.getLocationCategory());
        }
        if (request.getLocationTags() != null) {
            existing.setLocationTags(request.getLocationTags());
        }
        existing.setUpdatedAt(Instant.now());

        Location saved = locationRepository.save(existing);

        try {
            locationIndexService.updateIndex(saved);
            log.info("Updated location with index: {}", saved.getLocationId());
        } catch (Exception e) {
            log.warn("Failed to update index for location {}: {}", saved.getLocationId(), e.getMessage());
        }

        return saved;
    }

    public void deleteLocation(String locationId) {
        try {
            locationIndexService.removeFromIndex(locationId);
            log.info("Removed location from index: {}", locationId);
        } catch (Exception e) {
            log.warn("Failed to remove index for location {}: {}", locationId, e.getMessage());
        }

        locationRepository.deleteById(locationId);
    }

    public Optional<Location> getLocationById(String locationId) {
        return locationRepository.findById(locationId);
    }

    public List<Location> getAllLocations() {
        return locationRepository.findAll();
    }

    public List<Location> getLocationsByCategory(String category) {
        return locationRepository.findByCategory(category);
    }

    public List<Location> getLocationsByType(String type) {
        return locationRepository.findByType(type);
    }

    public List<Location> getLocationsByIds(List<String> locationIds) {
        return locationRepository.findByLocationIdIn(locationIds);
    }

    public void rebuildIndex(String locationId) {
        Optional<Location> locationOpt = locationRepository.findById(locationId);
        if (locationOpt.isPresent()) {
            locationIndexService.updateIndex(locationOpt.get());
            log.info("Rebuilt index for location: {}", locationId);
        }
    }

    public void rebuildAllIndexes() {
        List<Location> allLocations = locationRepository.findAll();
        int success = 0;
        int failed = 0;

        for (Location location : allLocations) {
            try {
                locationIndexService.buildIndex(location);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to index location {}: {}", location.getLocationId(), e.getMessage());
            }
        }

        log.info("Rebuilt all indexes - success: {}, failed: {}", success, failed);
    }
}
