package com.travelbooking.service;

import com.travelbooking.model.Spot;
import com.travelbooking.repository.SpotRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SpotService {

    private final SpotRepository spotRepository;

    @Transactional
    public Spot createSpot(Spot spot) {
        if (spot.getSpotId() == null || spot.getSpotId().isEmpty()) {
            spot.setSpotId(IdGenerator.generateSpotId());
        }
        if (spot.getSpotStatus() == null) {
            spot.setSpotStatus("active");
        }
        if (spot.getCreatedAt() == null) {
            spot.setCreatedAt(Instant.now());
        }
        return spotRepository.save(spot);
    }

    public List<Spot> getAllSpots() {
        return spotRepository.findAll();
    }

    public Optional<Spot> getSpotById(String spotId) {
        return spotRepository.findById(spotId);
    }

    public List<Spot> getActiveSpots() {
        return spotRepository.findBySpotStatus("active");
    }

    @Transactional
    public Spot updateSpot(String spotId, Spot spot) {
        Spot existing = spotRepository.findById(spotId)
                .orElseThrow(() -> new RuntimeException("景点不存在"));

        if (spot.getSpotName() != null) {
            existing.setSpotName(spot.getSpotName());
        }
        if (spot.getSpotLocation() != null) {
            existing.setSpotLocation(spot.getSpotLocation());
        }
        if (spot.getSpotType() != null) {
            existing.setSpotType(spot.getSpotType());
        }
        if (spot.getSpotStatus() != null) {
            existing.setSpotStatus(spot.getSpotStatus());
        }

        return spotRepository.save(existing);
    }

    public void deleteSpot(String spotId) {
        spotRepository.deleteById(spotId);
    }
}
