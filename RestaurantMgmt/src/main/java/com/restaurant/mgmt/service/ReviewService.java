package com.restaurant.mgmt.service;

import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Review;
import com.restaurant.mgmt.repository.ReviewRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private HistoryService historyService;

    public Review createReview(Review review) {
        if (review.getRating() < 1 || review.getRating() > 5) {
            throw new BusinessException("评分必须在1-5之间");
        }
        
        review.setReviewId(IdGenerator.generateReviewId());
        review.setCreatedAt(LocalDateTime.now());
        if (review.getStatus() == null) {
            review.setStatus("active");
        }
        
        Review saved = reviewRepository.save(review);
        historyService.recordHistory("review", saved.getReviewId(), "创建评价", 
            "创建评价, 评分: " + saved.getRating(), "system", "create", "success");
        
        return saved;
    }

    public Review updateReview(String reviewId, Review review) {
        Optional<Review> existingOpt = reviewRepository.findById(reviewId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("评价不存在");
        }
        
        Review existing = existingOpt.get();
        if (review.getRating() >= 1 && review.getRating() <= 5) {
            existing.setRating(review.getRating());
        }
        if (review.getFoodRating() > 0) {
            existing.setFoodRating(review.getFoodRating());
        }
        if (review.getServiceRating() > 0) {
            existing.setServiceRating(review.getServiceRating());
        }
        if (review.getEnvironmentRating() > 0) {
            existing.setEnvironmentRating(review.getEnvironmentRating());
        }
        if (review.getContent() != null) {
            existing.setContent(review.getContent());
        }
        if (review.getImages() != null) {
            existing.setImages(review.getImages());
        }
        
        return reviewRepository.save(existing);
    }

    public void deleteReview(String reviewId) {
        if (!reviewRepository.existsById(reviewId)) {
            throw new BusinessException("评价不存在");
        }
        reviewRepository.deleteById(reviewId);
    }

    public Review getReviewById(String reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException("评价不存在"));
    }

    public Optional<Review> getReviewByOrderId(String orderId) {
        return reviewRepository.findByOrderId(orderId);
    }

    public List<Review> getAllReviews() {
        return reviewRepository.findAll();
    }

    public List<Review> getReviewsByRating(int rating) {
        return reviewRepository.findByRating(rating);
    }

    public List<Review> getGoodReviews() {
        return reviewRepository.findByRatingGreaterThanEqual(4);
    }

    public List<Review> getActiveReviews() {
        return reviewRepository.findByStatus("active");
    }

    public List<Review> getReviewsByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return reviewRepository.findByCreatedAtBetween(startTime, endTime);
    }

    @Transactional
    public Review replyReview(String reviewId, String reply) {
        Review review = getReviewById(reviewId);
        review.setReply(reply);
        review.setReplyAt(LocalDateTime.now());
        
        historyService.recordHistory("review", reviewId, "回复评价", 
            "回复评价: " + reviewId, "system", "reply", "success");
        
        return reviewRepository.save(review);
    }

    @Transactional
    public Review hideReview(String reviewId) {
        Review review = getReviewById(reviewId);
        review.setStatus("hidden");
        return reviewRepository.save(review);
    }

    @Transactional
    public Review showReview(String reviewId) {
        Review review = getReviewById(reviewId);
        review.setStatus("active");
        return reviewRepository.save(review);
    }

    public double getAverageRating() {
        List<Review> reviews = getActiveReviews();
        if (reviews.isEmpty()) {
            return 0;
        }
        return reviews.stream().mapToInt(Review::getRating).average().orElse(0);
    }
}
