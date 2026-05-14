package com.travelbooking.repository;

import com.travelbooking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByTouristId(String touristId);
    List<Booking> findByRouteId(String routeId);
    Optional<Booking> findByBookingId(String bookingId);
    long countByBookingStatus(String bookingStatus);
}
