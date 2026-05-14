package com.hotelbooking.repository;

import com.hotelbooking.model.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInRepository extends JpaRepository<CheckIn, String> {
    Optional<CheckIn> findByBookingId(String bookingId);
    List<CheckIn> findByCheckinStatus(String status);

    @Query("SELECT COUNT(c) FROM CheckIn c WHERE EXISTS (SELECT b FROM Booking b WHERE b.bookingId = c.bookingId AND b.hotelId = :hotelId) " +
           "AND FUNCTION('DATE_FORMAT', c.checkinTime, '%Y-%m') = :month")
    Long countByHotelIdAndMonth(@Param("hotelId") String hotelId, @Param("month") String month);
}
