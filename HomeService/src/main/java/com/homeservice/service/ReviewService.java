package com.homeservice.service;

import com.homeservice.dto.ReviewCreateRequest;
import com.homeservice.dto.ReviewResponse;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Review;
import com.homeservice.enums.BookingStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.repository.ReviewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private ServiceHistoryService serviceHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    private final AtomicLong reviewCounter = new AtomicLong(0);

    @Transactional
    public ReviewResponse createReview(ReviewCreateRequest request) {
        Booking booking = bookingService.getBookingById(request.getBookingId());
        if (booking.getBookingStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Service is not completed yet");
        }
        if (booking.getIsReviewed()) {
            throw new BusinessException("Booking already reviewed");
        }
        if (request.getReviewRating() < 1 || request.getReviewRating() > 5) {
            throw new BusinessException("Rating must be between 1 and 5");
        }
        String reviewId = "review_" + String.format("%03d", reviewCounter.incrementAndGet());
        Review review = new Review(
            reviewId,
            request.getBookingId(),
            booking.getCustomerId(),
            booking.getStaffId(),
            request.getReviewRating(),
            request.getReviewContent()
        );
        reviewRepository.save(review);
        bookingService.markAsReviewed(request.getBookingId());
        staffService.incrementReviewCount(booking.getStaffId());
        staffService.updateStaffRating(booking.getStaffId(), request.getReviewRating().doubleValue());
        analyticsService.incrementReviewCount();
        serviceHistoryService.recordReviewHistory(
            "CREATE",
            "Review submitted with rating: " + request.getReviewRating(),
            request.getBookingId(),
            booking.getStaffId(),
            booking.getCustomerId()
        );
        return new ReviewResponse(reviewId, "submitted");
    }

    public List<Review> getAllReviews() {
        return reviewRepository.findAll();
    }

    public Review getReviewById(String reviewId) {
        return reviewRepository.findByReviewId(reviewId)
            .orElseThrow(() -> new com.homeservice.exception.ResourceNotFoundException("Review not found: " + reviewId));
    }

    public Review getReviewByBookingId(String bookingId) {
        return reviewRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new com.homeservice.exception.ResourceNotFoundException("Review not found for booking: " + bookingId));
    }

    public List<Review> getReviewsByStaffId(String staffId) {
        return reviewRepository.findByStaffId(staffId);
    }

    public List<Review> getReviewsByCustomerId(String customerId) {
        return reviewRepository.findByCustomerId(customerId);
    }

    public Double getStaffAverageRating(String staffId) {
        Double avgRating = reviewRepository.getAverageRatingByStaffId(staffId);
        return avgRating != null ? avgRating : 0.0;
    }
}
