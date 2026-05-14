package com.maplocation.service;

import com.maplocation.dto.MarkerCreateRequest;
import com.maplocation.model.Marker;
import com.maplocation.repository.MarkerRepository;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MarkerService {

    private final MarkerRepository markerRepository;

    public Marker createMarker(MarkerCreateRequest request) {
        Marker marker = Marker.builder()
                .markerId(IdGenerator.generateMarkerId())
                .locationId(request.getLocationId())
                .markerType(request.getMarkerType() != null ? request.getMarkerType() : "pin")
                .markerIcon(request.getMarkerIcon())
                .markerColor(request.getMarkerColor() != null ? request.getMarkerColor() : "red")
                .markerLabel(request.getMarkerLabel())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return markerRepository.save(marker);
    }

    public Marker updateMarker(String markerId, MarkerCreateRequest request) {
        Optional<Marker> existingOpt = markerRepository.findById(markerId);
        if (existingOpt.isEmpty()) {
            throw new RuntimeException("Marker not found: " + markerId);
        }

        Marker existing = existingOpt.get();
        if (request.getMarkerType() != null) {
            existing.setMarkerType(request.getMarkerType());
        }
        if (request.getMarkerIcon() != null) {
            existing.setMarkerIcon(request.getMarkerIcon());
        }
        if (request.getMarkerColor() != null) {
            existing.setMarkerColor(request.getMarkerColor());
        }
        if (request.getMarkerLabel() != null) {
            existing.setMarkerLabel(request.getMarkerLabel());
        }
        existing.setUpdatedAt(Instant.now());

        return markerRepository.save(existing);
    }

    public void deleteMarker(String markerId) {
        markerRepository.deleteById(markerId);
    }

    public Optional<Marker> getMarkerById(String markerId) {
        return markerRepository.findById(markerId);
    }

    public Optional<Marker> getMarkerByLocationId(String locationId) {
        return markerRepository.findByLocationId(locationId);
    }

    public List<Marker> getMarkersByLocationIds(List<String> locationIds) {
        return markerRepository.findByLocationIdIn(locationIds);
    }

    public List<Marker> getAllMarkers() {
        return markerRepository.findAll();
    }
}
