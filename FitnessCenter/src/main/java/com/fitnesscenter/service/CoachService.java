package com.fitnesscenter.service;

import com.fitnesscenter.model.Coach;
import com.fitnesscenter.repository.CoachRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CoachService {

    private final CoachRepository coachRepository;

    public CoachService(CoachRepository coachRepository) {
        this.coachRepository = coachRepository;
    }

    @Transactional
    public Coach createCoach(Coach coach) {
        coach.setCoachId(IdGenerator.generateCoachId());
        coach.setCreatedAt(Instant.now());
        if (coach.getCoachStatus() == null) {
            coach.setCoachStatus("available");
        }
        if (coach.getCoachRating() == null) {
            coach.setCoachRating(5.0);
        }
        if (coach.getBookingCount() == null) {
            coach.setBookingCount(0);
        }
        return coachRepository.save(coach);
    }

    @Transactional(readOnly = true)
    public Coach getCoachById(String coachId) {
        return coachRepository.findByCoachId(coachId)
                .orElseThrow(() -> new IllegalArgumentException("教练不存在"));
    }

    @Transactional(readOnly = true)
    public List<Coach> getAllCoaches() {
        return coachRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Coach> getAvailableCoaches() {
        return coachRepository.findByCoachStatus("available");
    }

    @Transactional(readOnly = true)
    public List<Coach> getCoachesByType(String coachType) {
        return coachRepository.findByCoachType(coachType);
    }

    @Transactional
    public Coach updateCoachStatus(String coachId, String status) {
        Coach coach = coachRepository.findByCoachId(coachId)
                .orElseThrow(() -> new IllegalArgumentException("教练不存在"));

        coach.setCoachStatus(status);
        return coachRepository.save(coach);
    }

    @Transactional
    public void incrementBookingCount(String coachId) {
        Coach coach = coachRepository.findByCoachId(coachId)
                .orElseThrow(() -> new IllegalArgumentException("教练不存在"));

        coach.setBookingCount(coach.getBookingCount() + 1);
        coachRepository.save(coach);
    }

    @Transactional(readOnly = true)
    public void validateCoachStatus(String coachId) {
        Coach coach = coachRepository.findByCoachId(coachId)
                .orElseThrow(() -> new IllegalArgumentException("教练不存在"));

        if (!"available".equals(coach.getCoachStatus())) {
            throw new IllegalStateException("教练不可用");
        }
    }
}
