package com.hotelbooking.service;

import com.hotelbooking.model.Booking;
import com.hotelbooking.model.Review;
import com.hotelbooking.repository.ReviewRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewService {
    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;

    public ReviewService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Transactional
    public Review submitReview(String bookingId, String hotelId, String customerName,
                                Integer rating, String comment) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new RuntimeException("评分必须在1-5之间");
        }

        Review review = new Review();
        review.setReviewId(IdGenerator.generateReviewId());
        review.setBookingId(bookingId);
        review.setHotelId(hotelId);
        review.setCustomerName(customerName);
        review.setRating(rating);
        review.setComment(comment);
        review.setCreatedAt(LocalDateTime.now());

        Review saved = reviewRepository.save(review);
        logger.info("评价提交成功: {}", saved.getReviewId());
        return saved;
    }

    public void requestReview(Booking booking) {
        logger.info("发送评价请求: 预订ID={}, 客户={}", booking.getBookingId(), booking.getCustomerName());
    }

    public List<Review> getReviewsByHotel(String hotelId) {
        return reviewRepository.findByHotelIdOrderByCreatedAtDesc(hotelId);
    }

    public List<Review> getReviewsByBooking(String bookingId) {
        return reviewRepository.findByBookingId(bookingId);
    }

    public double getAverageRating(String hotelId) {
        List<Review> reviews = reviewRepository.findByHotelId(hotelId);
        if (reviews.isEmpty()) {
            return 0.0;
        }
        return reviews.stream()
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);
    }
}
