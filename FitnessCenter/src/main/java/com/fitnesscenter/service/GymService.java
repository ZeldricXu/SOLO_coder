package com.fitnesscenter.service;

import com.fitnesscenter.model.Gym;
import com.fitnesscenter.repository.GymRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class GymService {

    private final GymRepository gymRepository;

    public GymService(GymRepository gymRepository) {
        this.gymRepository = gymRepository;
    }

    @Transactional
    public Gym createGym(Gym gym) {
        gym.setGymId(IdGenerator.generateGymId());
        gym.setCreatedAt(Instant.now());
        if (gym.getGymStatus() == null) {
            gym.setGymStatus("active");
        }
        return gymRepository.save(gym);
    }

    @Transactional(readOnly = true)
    public Gym getGymById(String gymId) {
        return gymRepository.findByGymId(gymId)
                .orElseThrow(() -> new IllegalArgumentException("场馆不存在"));
    }

    @Transactional(readOnly = true)
    public List<Gym> getAllGyms() {
        return gymRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Gym> getActiveGyms() {
        return gymRepository.findByGymStatus("active");
    }

    @Transactional
    public Gym updateGymStatus(String gymId, String status) {
        Gym gym = gymRepository.findByGymId(gymId)
                .orElseThrow(() -> new IllegalArgumentException("场馆不存在"));

        gym.setGymStatus(status);
        return gymRepository.save(gym);
    }

    @Transactional
    public Gym updateGym(String gymId, Gym gymDetails) {
        Gym gym = gymRepository.findByGymId(gymId)
                .orElseThrow(() -> new IllegalArgumentException("场馆不存在"));

        if (gymDetails.getGymName() != null) {
            gym.setGymName(gymDetails.getGymName());
        }
        if (gymDetails.getGymAddress() != null) {
            gym.setGymAddress(gymDetails.getGymAddress());
        }
        if (gymDetails.getGymPhone() != null) {
            gym.setGymPhone(gymDetails.getGymPhone());
        }
        if (gymDetails.getOpeningHours() != null) {
            gym.setOpeningHours(gymDetails.getOpeningHours());
        }
        if (gymDetails.getGymStatus() != null) {
            gym.setGymStatus(gymDetails.getGymStatus());
        }

        return gymRepository.save(gym);
    }
}
