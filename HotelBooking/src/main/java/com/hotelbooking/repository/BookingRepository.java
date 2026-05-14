package com.hotelbooking.repository;

import com.hotelbooking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByHotelId(String hotelId);
    List<Booking> findByRoomId(String roomId);
    List<Booking> findByBookingStatus(String status);
    List<Booking> findByHotelIdAndBookingStatus(String hotelId, String status);
    Optional<Booking> findByBookingId(String bookingId);

    @Query("SELECT b FROM Booking b WHERE b.roomId = :roomId AND b.bookingStatus IN ('pending', 'confirmed', 'checked_in') " +
           "AND :checkInDate < b.checkOutDate AND :checkOutDate > b.checkInDate")
    List<Booking> findConflictingBookings(@Param("roomId") String roomId,
                                          @Param("checkInDate") LocalDate checkInDate,
                                          @Param("checkOutDate") LocalDate checkOutDate);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.hotelId = :hotelId AND FUNCTION('DATE_FORMAT', b.createdAt, '%Y-%m') = :month")
    Long countByHotelIdAndMonth(@Param("hotelId") String hotelId, @Param("month") String month);

    @Query("SELECT b FROM Booking b WHERE b.customerPhone = :customerPhone ORDER BY b.createdAt DESC")
    List<Booking> findByCustomerPhoneOrderByCreatedAtDesc(@Param("customerPhone") String customerPhone);
}
