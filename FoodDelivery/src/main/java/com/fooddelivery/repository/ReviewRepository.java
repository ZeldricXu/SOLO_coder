package com.fooddelivery.repository;

import com.fooddelivery.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {
    Optional<Review> findByReviewId(String reviewId);
    Optional<Review> findByOrderId(String orderId);
    List<Review> findByRestaurantId(String restaurantId);
    List<Review> findByRiderId(String riderId);
    List<Review> findByUserId(String userId);
    boolean existsByOrderId(String orderId);
}
