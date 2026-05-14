package com.hotelbooking.repository;

import com.hotelbooking.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {
    List<Review> findByHotelId(String hotelId);
    List<Review> findByBookingId(String bookingId);
    List<Review> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}
