package com.homeservice.repository;

import com.homeservice.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByReviewId(String reviewId);
    Optional<Review> findByBookingId(String bookingId);
    List<Review> findByStaffId(String staffId);
    List<Review> findByCustomerId(String customerId);
    boolean existsByBookingId(String bookingId);
    @Query("SELECT COUNT(r) FROM Review r WHERE r.staffId = :staffId")
    Long countByStaffId(@Param("staffId") String staffId);
    @Query("SELECT AVG(r.reviewRating) FROM Review r WHERE r.staffId = :staffId")
    Double getAverageRatingByStaffId(@Param("staffId") String staffId);
    @Query("SELECT COUNT(r) FROM Review r")
    Long countTotalReviews();
}
