package com.travelbooking.service;

import com.travelbooking.model.Tourist;
import com.travelbooking.repository.TouristRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TouristService {

    private final TouristRepository touristRepository;

    @Transactional
    public Tourist createTourist(Tourist tourist) {
        if (tourist.getTouristId() == null || tourist.getTouristId().isEmpty()) {
            tourist.setTouristId(IdGenerator.generateTouristId());
        }
        if (tourist.getRegisteredAt() == null) {
            tourist.setRegisteredAt(Instant.now());
        }
        return touristRepository.save(tourist);
    }

    @Transactional
    public Tourist findOrCreateTourist(String touristName, String touristPhone, String touristIdType, String touristIdNumber) {
        if (touristPhone != null && !touristPhone.isEmpty()) {
            Optional<Tourist> existing = touristRepository.findByTouristNameAndTouristPhone(touristName, touristPhone);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Tourist tourist = new Tourist();
        tourist.setTouristId(IdGenerator.generateTouristId());
        tourist.setTouristName(touristName);
        tourist.setTouristPhone(touristPhone);
        tourist.setTouristIdType(touristIdType);
        tourist.setTouristIdNumber(touristIdNumber);
        tourist.setRegisteredAt(Instant.now());

        return touristRepository.save(tourist);
    }

    public List<Tourist> getAllTourists() {
        return touristRepository.findAll();
    }

    public Optional<Tourist> getTouristById(String touristId) {
        return touristRepository.findById(touristId);
    }

    @Transactional
    public Tourist updateTourist(String touristId, Tourist tourist) {
        Tourist existing = touristRepository.findById(touristId)
                .orElseThrow(() -> new RuntimeException("游客不存在"));

        if (tourist.getTouristName() != null) {
            existing.setTouristName(tourist.getTouristName());
        }
        if (tourist.getTouristPhone() != null) {
            existing.setTouristPhone(tourist.getTouristPhone());
        }
        if (tourist.getTouristIdType() != null) {
            existing.setTouristIdType(tourist.getTouristIdType());
        }
        if (tourist.getTouristIdNumber() != null) {
            existing.setTouristIdNumber(tourist.getTouristIdNumber());
        }

        return touristRepository.save(existing);
    }

    public void deleteTourist(String touristId) {
        touristRepository.deleteById(touristId);
    }
}
