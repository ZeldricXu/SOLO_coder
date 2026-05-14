package com.travelbooking.service;

import com.travelbooking.model.Guide;
import com.travelbooking.repository.GuideRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GuideService {

    private final GuideRepository guideRepository;

    @Transactional
    public Guide createGuide(Guide guide) {
        if (guide.getGuideId() == null || guide.getGuideId().isEmpty()) {
            guide.setGuideId(IdGenerator.generateGuideId());
        }
        if (guide.getGuideStatus() == null) {
            guide.setGuideStatus("available");
        }
        if (guide.getGuideRating() == null) {
            guide.setGuideRating(new BigDecimal("4.0"));
        }
        if (guide.getGuideCount() == null) {
            guide.setGuideCount(0);
        }
        if (guide.getCompletedCount() == null) {
            guide.setCompletedCount(0);
        }
        if (guide.getCreatedAt() == null) {
            guide.setCreatedAt(Instant.now());
        }
        return guideRepository.save(guide);
    }

    @Transactional
    public Guide assignGuide() {
        List<Guide> guides = guideRepository.findByGuideStatus("available");
        if (guides.isEmpty()) {
            return null;
        }

        return guides.stream()
                .max(Comparator.comparing(Guide::getGuideRating))
                .orElse(null);
    }

    @Transactional
    public void incrementGuideCount(String guideId) {
        Guide guide = guideRepository.findById(guideId)
                .orElseThrow(() -> new RuntimeException("导游不存在"));
        guide.setGuideCount(guide.getGuideCount() + 1);
        guideRepository.save(guide);
    }

    @Transactional
    public void incrementCompletedCount(String guideId) {
        Guide guide = guideRepository.findById(guideId)
                .orElseThrow(() -> new RuntimeException("导游不存在"));
        guide.setCompletedCount(guide.getCompletedCount() + 1);
        guideRepository.save(guide);
    }

    public List<Guide> getAllGuides() {
        return guideRepository.findAll();
    }

    public Optional<Guide> getGuideById(String guideId) {
        return guideRepository.findById(guideId);
    }

    public List<Guide> getAvailableGuides() {
        return guideRepository.findByGuideStatus("available");
    }

    @Transactional
    public Guide updateGuide(String guideId, Guide guide) {
        Guide existing = guideRepository.findById(guideId)
                .orElseThrow(() -> new RuntimeException("导游不存在"));

        if (guide.getGuideName() != null) {
            existing.setGuideName(guide.getGuideName());
        }
        if (guide.getGuidePhone() != null) {
            existing.setGuidePhone(guide.getGuidePhone());
        }
        if (guide.getGuideRating() != null) {
            existing.setGuideRating(guide.getGuideRating());
        }
        if (guide.getGuideStatus() != null) {
            existing.setGuideStatus(guide.getGuideStatus());
        }

        return guideRepository.save(existing);
    }

    public void deleteGuide(String guideId) {
        guideRepository.deleteById(guideId);
    }
}
