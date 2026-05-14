package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {
    Optional<Review> findByOrderId(String orderId);
    List<Review> findByRating(int rating);
    List<Review> findByRatingGreaterThanEqual(int rating);
    List<Review> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<Review> findByStatus(String status);
}
